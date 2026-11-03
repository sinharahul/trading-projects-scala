package com.backoffice.marketdata.monitoring

import io.delta.tables._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

object TableCounts extends App {
  val spark = SparkSession.builder()
    .appName("TableCounts")
    .master("local[*]")
    .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
    .config("spark.sql.catalog.spark_catalog",
      "org.apache.spark.sql.delta.catalog.DeltaCatalog")
    .getOrCreate()
  spark.sparkContext.setLogLevel("ERROR")

  Seq("data/delta/bronze_ticks", "data/delta/silver_ticks",
    "data/delta/quarantine_ticks").foreach { path =>
    val count = spark.read.format("delta").load(path).count()
    println(s"$path: $count rows")
  }
  val quarantine = spark.read.format("delta").load("data/delta/quarantine_ticks")
  quarantine.groupBy("validation_reason").count().show(false)
  spark.stop()
}
