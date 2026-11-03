package com.backoffice.marketdata.streaming

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._

object Deduplication {

  /** Drop duplicate ticks, keeping the first occurrence of each
    * (symbol, exchange, sequence_number) triple. The watermark bounds
    * the state: Spark may discard dedup state for event times older than
    * the watermark, which is what keeps the job's memory bounded.
    *
    * The 10-minute watermark is a business decision, not a magic number.
    * Vendor feeds rarely deliver a trade more than a few minutes late;
    * corrections, which are a different mechanism, arrive through a
    * separate channel. Ten minutes covers late delivery with margin,
    * while keeping state small. If your feed's lateness profile differs,
    * measure it first (Step 8 shows how) and then set the watermark.
    */
  def deduplicate(ticks: DataFrame): DataFrame = {
    ticks
      .withWatermark("event_time", "10 minutes")
      .dropDuplicates("symbol", "exchange", "sequence_number")
  }
}
