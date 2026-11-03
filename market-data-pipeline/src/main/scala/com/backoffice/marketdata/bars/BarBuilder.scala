package com.backoffice.marketdata.bars

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._

/** Builds one-minute OHLCV bars from clean trade ticks.
  *
  * Only TRADE events are used. Quotes are excluded by the filter, not by
  * accident: a bar's volume is traded volume, and its prices are trade
  * prices. Including quotes would corrupt all three.
  */
object BarBuilder {

  /** Build bars from a DataFrame of silver ticks. The input must have
    * symbol, event_time, trade_price, and trade_size columns, and must
    * already be deduplicated. The output has one row per symbol per
    * one-minute window that contains at least one trade.
    */
  def buildBars(trades: DataFrame): DataFrame = {
    val onlyTrades = trades.filter(col("event_type") === "TRADE")

    // Stage 1: order-independent aggregates per symbol per minute.
    val windowed = onlyTrades
      .withColumn("window", window(col("event_time"), "1 minute"))
      .groupBy(col("symbol"), col("window"))
      .agg(
        max("trade_price").as("high_price"),
        min("trade_price").as("low_price"),
        sum("trade_size").as("volume"),
        count("*").as("trade_count")
      )

    // Stage 2: open and close via row numbers ordered by event time.
    // A window function partitions by symbol and minute window and
    // orders by event_time, so row 1 is the first trade (open) and the
    // last row is the final trade (close).
    val specAsc = Window
      .partitionBy(col("symbol"), window(col("event_time"), "1 minute"))
      .orderBy(col("event_time").asc, col("sequence_number").asc)
    val specDesc = Window
      .partitionBy(col("symbol"), window(col("event_time"), "1 minute"))
      .orderBy(col("event_time").desc, col("sequence_number").desc)

    val ranked = onlyTrades
      .withColumn("rn_asc", row_number().over(specAsc))
      .withColumn("rn_desc", row_number().over(specDesc))

    val opens = ranked.filter(col("rn_asc") === 1)
      .select(
        col("symbol"),
        window(col("event_time"), "1 minute").as("window"),
        col("trade_price").as("open_price")
      )
    val closes = ranked.filter(col("rn_desc") === 1)
      .select(
        col("symbol"),
        window(col("event_time"), "1 minute").as("window"),
        col("trade_price").as("close_price")
      )

    windowed
      .join(opens, Seq("symbol", "window"), "inner")
      .join(closes, Seq("symbol", "window"), "inner")
      .select(
        col("symbol"),
        col("window.start").as("window_start"),
        col("window.end").as("window_end"),
        col("open_price"),
        col("high_price"),
        col("low_price"),
        col("close_price"),
        col("volume"),
        col("trade_count")
      )
  }
}
