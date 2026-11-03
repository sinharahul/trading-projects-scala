package com.trading.surveillance.streaming

import com.trading.surveillance.detectors._
import com.trading.surveillance.scoring.{AlertScorer, SuppressionEngine}
import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger
import io.delta.tables.DeltaTable

object SurveillanceApp {
  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load()
    val brokers = sys.env.getOrElse("KAFKA_BROKERS", "localhost:9092")
    val base = sys.env.getOrElse("DELTA_BASE", "data/delta")
    val alertsPath = s"$base/surveillance/alerts"
    val casesPath = s"$base/surveillance/cases"
    val volumesPath = s"$base/surveillance/daily_volumes"

    val spark = SparkSession.builder()
      .appName("trade-surveillance")
      .master("local[*]")
      .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
      .config("spark.sql.catalog.spark_catalog",
        "org.apache.spark.sql.delta.catalog.DeltaCatalog")
      .config("spark.sql.shuffle.partitions", "8")
      .getOrCreate()
    import spark.implicits._

    // --- Detector queries ---
    val trades = StreamingCommon.kafkaTrades(spark, brokers)
    val orders = StreamingCommon.kafkaOrders(spark, brokers)

    // Wash trades: trades stream, foreachBatch with the tested batch function.
    val washQuery = trades.writeStream
      .trigger(Trigger.ProcessingTime("30 seconds"))
      .foreachBatch { (batch: DataFrame, _: Long) =>
        if (!batch.isEmpty) {
          val alerts = WashTradeDetector.detect(batch)(spark)
          if (!alerts.isEmpty)
            alerts.write.format("delta").mode("append").save(alertsPath)
        }
      }
      .option("checkpointLocation", s"$base/checkpoints/wash")
      .start()

    // Spoofing: orders stream. VWAP is approximated here from a static map
    // loaded from configuration; in production it comes from a market-data feed.
    val vwapBySymbol = Map(
      "ACME" -> 152.40, "GLOB" -> 87.15, "INNO" -> 210.80,
      "VERT" -> 45.30, "NOVA" -> 310.55
    )
    val spoofQuery = orders.writeStream
      .trigger(Trigger.ProcessingTime("30 seconds"))
      .foreachBatch { (batch: DataFrame, _: Long) =>
        if (!batch.isEmpty) {
          val alerts = SpoofingDetector.detect(batch, vwapBySymbol)(spark)
          if (!alerts.isEmpty)
            alerts.write.format("delta").mode("append").save(alertsPath)
        }
      }
      .option("checkpointLocation", s"$base/checkpoints/spoof")
      .start()

    // Marking the close: trades stream, evaluated per micro-batch.
    val markingQuery = trades.writeStream
      .trigger(Trigger.ProcessingTime("30 seconds"))
      .foreachBatch { (batch: DataFrame, _: Long) =>
        if (!batch.isEmpty) {
          val alerts = MarkingCloseDetector.detect(batch)(spark)
          if (!alerts.isEmpty)
            alerts.write.format("delta").mode("append").save(alertsPath)
        }
      }
      .option("checkpointLocation", s"$base/checkpoints/marking")
      .start()

    // Daily volumes: maintained for the unusual-volume detector.
    val dailyVolumesQuery = trades
      .withWatermark("tradeTime", "1 hour")
      .groupBy(col("symbol"), window(col("tradeTime"), "1 day").as("dayWindow"))
      .agg(sum("quantity").as("volume"))
      .select(
        col("symbol"),
        to_date(col("dayWindow.start")).as("day"),
        col("volume")
      )
      .writeStream
      .format("delta")
      .outputMode("append")
      .option("checkpointLocation", s"$base/checkpoints/volumes")
      .start(volumesPath)

    // The scoring and suppression step runs as a batch job over the alerts
    // table on a schedule (hourly in production, on demand in this project).
    // It reads NEW alerts, scores them, suppresses duplicates and cooldowns,
    // and writes scored alerts to the triage queue. The case manager in
    // Step 7 consumes the queue.
    spark.streams.awaitAnyTermination()
  }
}
