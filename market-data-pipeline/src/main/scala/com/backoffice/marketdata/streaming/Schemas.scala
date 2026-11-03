package com.backoffice.marketdata.streaming

import org.apache.spark.sql.types._

object Schemas {

  /** A single trade as reported by an exchange or vendor feed. */
  val tradeSchema: StructType = StructType(Seq(
    StructField("event_type", StringType, nullable = false),
    StructField("symbol", StringType, nullable = false),
    StructField("exchange", StringType, nullable = false),
    StructField("event_time", TimestampType, nullable = false),
    StructField("sequence_number", LongType, nullable = false),
    StructField("trade_price", DoubleType, nullable = false),
    StructField("trade_size", LongType, nullable = false),
    StructField("trade_condition", StringType, nullable = true)
  ))

  /** A single quote (best bid and offer snapshot). */
  val quoteSchema: StructType = StructType(Seq(
    StructField("event_type", StringType, nullable = false),
    StructField("symbol", StringType, nullable = false),
    StructField("exchange", StringType, nullable = false),
    StructField("event_time", TimestampType, nullable = false),
    StructField("sequence_number", LongType, nullable = false),
    StructField("bid_price", DoubleType, nullable = false),
    StructField("bid_size", LongType, nullable = false),
    StructField("ask_price", DoubleType, nullable = false),
    StructField("ask_size", LongType, nullable = false)
  ))

  /** The unified tick: trades and quotes share one Kafka topic and one
    * bronze table, distinguished by event_type. Nullable price fields let
    * one schema carry both shapes without a union of unrelated columns.
    */
  val tickSchema: StructType = StructType(Seq(
    StructField("event_type", StringType, nullable = false),
    StructField("symbol", StringType, nullable = false),
    StructField("exchange", StringType, nullable = false),
    StructField("event_time", TimestampType, nullable = false),
    StructField("sequence_number", LongType, nullable = false),
    StructField("trade_price", DoubleType, nullable = true),
    StructField("trade_size", LongType, nullable = true),
    StructField("trade_condition", StringType, nullable = true),
    StructField("bid_price", DoubleType, nullable = true),
    StructField("bid_size", LongType, nullable = true),
    StructField("ask_price", DoubleType, nullable = true),
    StructField("ask_size", LongType, nullable = true)
  ))

  /** One-minute OHLCV bar, the gold output of this project. */
  val barSchema: StructType = StructType(Seq(
    StructField("symbol", StringType, nullable = false),
    StructField("window_start", TimestampType, nullable = false),
    StructField("window_end", TimestampType, nullable = false),
    StructField("open_price", DoubleType, nullable = false),
    StructField("high_price", DoubleType, nullable = false),
    StructField("low_price", DoubleType, nullable = false),
    StructField("close_price", DoubleType, nullable = false),
    StructField("volume", LongType, nullable = false),
    StructField("trade_count", LongType, nullable = false)
  ))
}
