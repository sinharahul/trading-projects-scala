package com.trading.surveillance.detectors

import com.trading.surveillance.streaming.StreamingCommon
import io.delta.tables.DeltaTable
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger

object DetectorRunner {
  def washTradeQuery(
    spark: SparkSession, brokers: String, alertsPath: String
  ): org.apache.spark.sql.streaming.StreamingQuery = {
    val trades = StreamingCommon.kafkaTrades(spark, brokers)
    trades.writeStream
      .trigger(Trigger.ProcessingTime("30 seconds"))
      .foreachBatch { (batch: DataFrame, batchId: Long) =>
        if (!batch.isEmpty) {
          val alerts = WashTradeDetector.detect(batch)(spark)
          if (!alerts.isEmpty) {
            alerts.write.format("delta").mode("append").save(alertsPath)
          }
        }
      }
      .option("checkpointLocation", s"$alertsPath-checkpoint-wash")
      .start()
  }
}
