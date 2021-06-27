package etljobs.spark

import DataFormat._
import common._
import etljobs.common.{EntityPattern, SparkOption, HadoopCfg}

import mainargs.{main, ParserForMethods}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.functions._
import io.delta.tables._

import java.net.URI

object FileToDataset extends App {
  @main
  def run(params: SparkCopyCfg) =
    sparkCopy(params)

  private def mergeTable(
      tablePath: String,
      schema: Option[StructType],
      spark: SparkSession,
      dedupKey: String,
      input: DataFrame,
      partitionBy: String
  ) = {
    val target =
      DeltaTable
        .createIfNotExists(spark)
        .location(tablePath)

    val targetWithSchema = schema
      .fold(target)(target.addColumns)
      .partitionedBy(partitionBy)
      .execute()
      .as("target")

    targetWithSchema
      .merge(input.as("updates"), s"target.$dedupKey = updates.$dedupKey")
      .whenMatched()
      .updateAll()
      .whenNotMatched()
      .insertAll()
      .execute()
  }

  private def loadFileToSpark(
      entity: EntityPattern,
      spark: SparkSession,
      cfg: SparkCopyCfg,
      input: URI,
      output: URI,
      saveMode: SaveMode
  ) = {
    val inputData = spark.read.format(cfg.inputFormat.toSparkFormat)
    val options = List(
      SparkOption("pathGlobFilter", entity.globPattern),
      SparkOption("inferSchema", "true")
    )
    val inputDataWithOptions =
      (cfg.readerOptions.getOrElse(Nil) ++ options)
        .foldLeft(inputData) { case (acc, opt) =>
          acc.option(opt.name, opt.value)
        }

    val inputDF = inputDataWithOptions
      .load(input.toString())
      .withColumn("date", current_date())

    lazy val inputDFToWrite =
      inputDF.write.partitionBy(cfg.partitionBy).mode(saveMode)

    val writer = cfg.saveFormat match {
      case CSV     => inputDFToWrite.csv _
      case JSON    => inputDFToWrite.json _
      case Parquet => inputDFToWrite.parquet _
      case Delta if entity.dedupKey.isDefined =>
        tablePath: String =>
          lazy val schema = cfg.schemaPath.map(p => getSchema(p, entity.name))
          entity.dedupKey.foreach { key =>
            mergeTable(tablePath, schema, spark, key, inputDF, cfg.partitionBy)
          }
      case _ =>
        path: String =>
          inputDFToWrite.format(cfg.saveFormat.toSparkFormat).save(path)
    }

    writer(output.toString())
  }

  def sparkCopy(cfg: SparkCopyCfg) = {
    val (input, output) = getInOutPaths(cfg.fileCopy)
    println(s"input path: $input")
    val sparkSession =
      sparkWithConfig(cfg.fileCopy.hadoopConfig).getOrCreate()

    useResource(sparkSession) { spark =>
      lazy val saveMode = getSaveMode(cfg.fileCopy.overwrite.value)

      cfg.fileCopy.entityPatterns.foreach { p =>
        val entityOutPath = output.resolve(p.name)
        println(s"output path: $entityOutPath")
        loadFileToSpark(p, spark, cfg, input, entityOutPath, saveMode)
      }

      if (requireMove(cfg)) {
        val conf = HadoopCfg.get(cfg.fileCopy.hadoopConfig)
        moveFiles(
          conf,
          cfg.fileCopy.entityPatterns,
          cfg.fileCopy.processedDir,
          input
        )
      }
    }
  }

  private def getSaveMode(overwrite: Boolean) =
    overwrite match {
      case true => SaveMode.Overwrite
      case _    => SaveMode.ErrorIfExists
    }

  ParserForMethods(this).runOrExit(args)
}
