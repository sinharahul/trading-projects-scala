package com.backoffice.marketdata.streaming

import io.delta.tables._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger

object SilverStreamingJob {

  val BronzePath = "data/delta/bronze_ticks"
  val SilverPath = "data/delta/silver_ticks"
  val QuarantinePath = "data/delta/quarantine_ticks"

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder()
      .appName("SilverStreamingJob")
      .master("local[*]")
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog",
        "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .config("spark.sql.shuffle.partitions", "4")
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")

    // Read bronze as a stream: new bronze rows flow into this job.
    val bronzeStream = spark.readStream
      .format("delta")
      .load(BronzePath)

    // Parse the JSON payload against the tick schema. from_json never
    // throws on a bad record; unparsable fields become null, and the
    // validation step below catches them.
    val parsed = bronzeStream
      .withColumn("tick", from_json(col("raw_json"), Schemas.tickSchema))
      .select(
        col("kafka_offset"),
        col("kafka_timestamp").as("ingested_at"),
        col("tick.*")
      )

    val withReason = parsed
      .withColumn("validation_reason",
        Validation.validationReason)

    val valid = withReason.filter(col("validation_reason").isNull)
      .drop("validation_reason")

    val invalid = withReason.filter(col("validation_reason").isNotNull)
      .select(
        col("kafka_offset"),
        col("ingested_at"),
        col("validation_reason"),
        col("symbol"),
        col("event_type"),
        col("event_time"),
        // Keep the raw payload so the record can be inspected and,
        // if the data turns out to be good after all, replayed.
        to_json(struct(col("event_type"), col("symbol"), col("exchange"),
          col("event_time"), col("sequence_number"), col("trade_price"),
          col("trade_size"), col("trade_condition"), col("bid_price"),
          col("bid_size"), col("ask_price"), col("ask_size")))
          .as("raw_payload")
      )

    val deduped = Deduplication.deduplicate(valid)

    val silverQuery = deduped.writeStream
      .format("delta")
      .outputMode("append")
      .option("checkpointLocation", "data/checkpoints/silver")
      .trigger(Trigger.ProcessingTime("10 seconds"))
      .start(SilverPath)

    val quarantineQuery = invalid.writeStream
      .format("delta")
      .outputMode("append")
      .option("checkpointLocation", "data/checkpoints/quarantine")
      .trigger(Trigger.ProcessingTime("10 seconds"))
      .start(QuarantinePath)

    println("Silver and quarantine queries started.")
    spark.streams.awaitAnyTermination()
  }
}
