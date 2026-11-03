package com.backoffice.marketdata

import com.backoffice.marketdata.bars.BarBuilder
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BarBuilderSpec extends AnyFlatSpec with Matchers {

  lazy val spark: SparkSession = SparkSession.builder()
    .appName("BarBuilderSpec")
    .master("local[*]")
    .config("spark.sql.shuffle.partitions", "2")
    .getOrCreate()
  spark.sparkContext.setLogLevel("ERROR")
  import spark.implicits._

  "BarBuilder" should "compute OHLCV from trades in one minute" in {
    // Deliberately out of order: the last trade by event time is
    // listed first, and the first trade is listed last. A builder that
    // uses arrival order instead of event time will fail this test.
    val df = Seq(
      ("TRADE", "AAPL", "2026-10-30 14:30:45", 3L, 263.10, 200L),
      ("TRADE", "AAPL", "2026-10-30 14:30:10", 1L, 262.40, 100L),
      ("TRADE", "AAPL", "2026-10-30 14:30:30", 2L, 262.90, 150L),
      // A quote in the same minute must NOT affect the bar.
      ("QUOTE", "AAPL", "2026-10-30 14:30:20", 4L, 0.0, 0L)
    ).toDF("event_type", "symbol", "event_time", "sequence_number",
      "trade_price", "trade_size")
      .withColumn("event_time", to_timestamp(col("event_time")))

    val bars = BarBuilder.buildBars(df)
    val rows = bars.collect()
    rows should have length 1
    val row = rows.head
    row.getAs[String]("symbol") shouldBe "AAPL"
    row.getAs[Double]("open_price") shouldBe 262.40 +- 0.001
    row.getAs[Double]("high_price") shouldBe 263.10 +- 0.001
    row.getAs[Double]("low_price") shouldBe 262.40 +- 0.001
    row.getAs[Double]("close_price") shouldBe 263.10 +- 0.001
    row.getAs[Long]("volume") shouldBe 450L
    row.getAs[Long]("trade_count") shouldBe 3L
  }

  it should "produce one bar per symbol per minute" in {
    val df = Seq(
      ("TRADE", "AAPL", "2026-10-30 14:30:10", 1L, 262.40, 100L),
      ("TRADE", "AAPL", "2026-10-30 14:31:10", 2L, 262.50, 100L),
      ("TRADE", "MSFT", "2026-10-30 14:30:10", 1L, 512.15, 100L)
    ).toDF("event_type", "symbol", "event_time", "sequence_number",
      "trade_price", "trade_size")
      .withColumn("event_time", to_timestamp(col("event_time")))

    BarBuilder.buildBars(df).count() shouldBe 3L
  }
}
