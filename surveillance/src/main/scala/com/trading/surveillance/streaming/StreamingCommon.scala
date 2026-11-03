package com.trading.surveillance.streaming

import com.trading.surveillance.model.Schemas
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._

object StreamingCommon {
  def kafkaTrades(
    spark: SparkSession, brokers: String
  ): DataFrame = {
    spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", brokers)
      .option("subscribe", "trades")
      .option("startingOffsets", "earliest")
      .load()
      .selectExpr("CAST(value AS STRING) as json")
      .select(from_json(col("json"), Schemas.tradeSchema).as("t"))
      .select("t.*")
      // Dead-letter discipline: rows that failed parsing arrive with null keys.
      // Filter them out here; the dead-letter writer in SurveillanceApp logs
      // them separately so corrupt data is visible, not silent.
      .filter(col("tradeId").isNotNull)
  }

  def kafkaOrders(
    spark: SparkSession, brokers: String
  ): DataFrame = {
    spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", brokers)
      .option("subscribe", "orders")
      .option("startingOffsets", "earliest")
      .load()
      .selectExpr("CAST(value AS STRING) as json")
      .select(from_json(col("json"), Schemas.orderEventSchema).as("o"))
      .select("o.*")
      .filter(col("orderId").isNotNull)
  }
}
