package com.backoffice.marketdata

import com.backoffice.marketdata.streaming.Validation
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ValidationSpec extends AnyFlatSpec with Matchers {

  lazy val spark: SparkSession = SparkSession.builder()
    .appName("ValidationSpec")
    .master("local[*]")
    .config("spark.sql.shuffle.partitions", "2")
    .getOrCreate()
  spark.sparkContext.setLogLevel("ERROR")
  import spark.implicits._

  "Validation" should "accept a well-formed trade" in {
    val df = Seq(
      ("TRADE", "AAPL", "NASDAQ", "2026-10-30 14:30:00", 1L,
        Some(262.40), Some(100L), Some("@"),
        None, None, None, None)
    ).toDF("event_type", "symbol", "exchange", "event_time",
      "sequence_number", "trade_price", "trade_size", "trade_condition",
      "bid_price", "bid_size", "ask_price", "ask_size")
      .withColumn("event_time", to_timestamp(col("event_time")))

    val result = df.withColumn("reason", Validation.validationReason)
    result.select("reason").as[String].collect() should contain only null
  }

  it should "quarantine a trade with a negative price" in {
    val df = Seq(
      ("TRADE", "AAPL", "NASDAQ", "2026-10-30 14:30:00", 1L,
        Some(-5.00), Some(100L), Some("@"),
        None, None, None, None)
    ).toDF("event_type", "symbol", "exchange", "event_time",
      "sequence_number", "trade_price", "trade_size", "trade_condition",
      "bid_price", "bid_size", "ask_price", "ask_size")
      .withColumn("event_time", to_timestamp(col("event_time")))

    val result = df.withColumn("reason", Validation.validationReason)
    result.select("reason").as[String].collect() should
      contain only "invalid_trade_price"
  }

  it should "quarantine a crossed market" in {
    val df = Seq(
      ("QUOTE", "AAPL", "NASDAQ", "2026-10-30 14:30:00", 1L,
        None, None, None,
        Some(262.50), Some(100L), Some(262.40), Some(100L))
    ).toDF("event_type", "symbol", "exchange", "event_time",
      "sequence_number", "trade_price", "trade_size", "trade_condition",
      "bid_price", "bid_size", "ask_price", "ask_size")
      .withColumn("event_time", to_timestamp(col("event_time")))

    val result = df.withColumn("reason", Validation.validationReason)
    result.select("reason").as[String].collect() should
      contain only "crossed_market"
  }

  it should "quarantine a record with a missing symbol" in {
    val df = Seq(
      ("TRADE", null, "NASDAQ", "2026-10-30 14:30:00", 1L,
        Some(262.40), Some(100L), Some("@"),
        None, None, None, None)
    ).toDF("event_type", "symbol", "exchange", "event_time",
      "sequence_number", "trade_price", "trade_size", "trade_condition",
      "bid_price", "bid_size", "ask_price", "ask_size")
      .withColumn("event_time", to_timestamp(col("event_time")))

    val result = df.withColumn("reason", Validation.validationReason)
    result.select("reason").as[String].collect() should
      contain only "missing_symbol"
  }
}
