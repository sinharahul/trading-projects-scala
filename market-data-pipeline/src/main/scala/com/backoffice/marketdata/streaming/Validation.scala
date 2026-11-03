package com.backoffice.marketdata.streaming

import org.apache.spark.sql.Column
import org.apache.spark.sql.functions._

/** Validation rules for market ticks. Each rule produces a nullable
  * reason string: null means the rule passed, a message means it failed.
  * Keeping reasons as data (not exceptions) is what lets the pipeline
  * quarantine bad records instead of crashing on them.
  */
object Validation {

  /** Returns a column of reason strings, or null when the record is valid.
    * Rules are checked in order and the first failure wins, so the
    * quarantine table tells you the single most useful thing to fix.
    */
  def validationReason: Column = {
    when(col("event_type").isNull, lit("missing_event_type"))
      .when(!col("event_type").isin("TRADE", "QUOTE"), lit("unknown_event_type"))
      .when(col("symbol").isNull || length(trim(col("symbol"))) === 0,
        lit("missing_symbol"))
      .when(col("event_time").isNull, lit("missing_event_time"))
      .when(col("sequence_number").isNull, lit("missing_sequence_number"))
      // Trade-specific rules: a trade must carry a positive price and size.
      .when(col("event_type") === "TRADE" &&
        (col("trade_price").isNull || col("trade_price") <= 0),
        lit("invalid_trade_price"))
      .when(col("event_type") === "TRADE" &&
        (col("trade_size").isNull || col("trade_size") <= 0),
        lit("invalid_trade_size"))
      // Quote-specific rules: bid and ask must be present, positive, and
      // crossed markets (bid above ask) are invalid.
      .when(col("event_type") === "QUOTE" &&
        (col("bid_price").isNull || col("bid_price") <= 0),
        lit("invalid_bid_price"))
      .when(col("event_type") === "QUOTE" &&
        (col("ask_price").isNull || col("ask_price") <= 0),
        lit("invalid_ask_price"))
      .when(col("event_type") === "QUOTE" &&
        col("bid_price") > col("ask_price"),
        lit("crossed_market"))
      .otherwise(lit(null))
  }
}
