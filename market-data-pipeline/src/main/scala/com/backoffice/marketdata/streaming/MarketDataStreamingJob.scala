package com.backoffice.marketdata.streaming

import io.delta.tables._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger

object MarketDataStreamingJob {

  val BronzePath = "data/delta/bronze_ticks"
  val SilverPath = "data/delta/silver_ticks"
  val QuarantinePath = "data/delta/quarantine_ticks"
  val CheckpointBase = "data/checkpoints"

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("MarketDataStreamingJob")
      .master("local[*]")
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog",
        "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .config("spark.sql.shuffle.partitions", "4")
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")

    import spark.implicits._

    // Read the Kafka topic as a stream. The value column holds the JSON
    // string; key, topic, partition, offset, and timestamp are metadata
    // that bronze preserves for audit and replay.
    val kafkaStream = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", "localhost:9092")
      .option("subscribe", "market-data-raw")
      .option("startingOffsets", "earliest")
      .option("failOnDataLoss", "false")
      .load()

    // Bronze: store the record exactly as received. No parsing, no
    // cleaning, no dropping. If parsing fails later, bronze still holds
    // the evidence, and the job that failed can be fixed and rerun.
    val bronze = kafkaStream.select(
      col("key").cast("string").as("kafka_key"),
      col("topic").as("kafka_topic"),
      col("partition").as("kafka_partition"),
      col("offset").as("kafka_offset"),
      col("timestamp").as("kafka_timestamp"),
      col("value").cast("string").as("raw_json")
    )

    val bronzeQuery = bronze.writeStream
      .format("delta")
      .outputMode("append")
      .option("checkpointLocation", s"$CheckpointBase/bronze")
      .trigger(Trigger.ProcessingTime("10 seconds"))
      .start(BronzePath)

    println("Streaming job started. Bronze query is running.")
    println("Press Ctrl+C to stop. Checkpoints are preserved across restarts.")
    spark.streams.awaitAnyTermination()
  }
}
