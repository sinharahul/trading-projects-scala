package com.backoffice.marketdata.bars

import io.delta.tables._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger

object BarBuilderJob {

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("BarBuilderJob")
      .master("local[*]")
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog",
        "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .config("spark.sql.shuffle.partitions", "4")
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")

    val silverStream = spark.readStream
      .format("delta")
      .load("data/delta/silver_ticks")

    // Bars are windowed by event time, so the same watermark discipline
    // applies: a 10-minute watermark bounds how long a minute window
    // stays open for late trades.
    val withWatermark = silverStream
      .withWatermark("event_time", "10 minutes")

    val bars = BarBuilder.buildBars(withWatermark)

    val query = bars.writeStream
      .format("delta")
      .outputMode("append")
      .option("checkpointLocation", "data/checkpoints/bars")
      .trigger(Trigger.ProcessingTime("10 seconds"))
      .start("data/delta/bars_1min")

    println("Bar builder started.")
    spark.streams.awaitAnyTermination()
  }
}
