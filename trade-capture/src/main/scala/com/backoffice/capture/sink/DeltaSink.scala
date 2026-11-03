package com.backoffice.capture.sink

import com.backoffice.capture.model.EnrichedTrade
import io.delta.tables.DeltaTable
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.slf4j.LoggerFactory

object DeltaSink {

  private val log = LoggerFactory.getLogger(getClass)

  val schema: StructType = StructType(Seq(
    StructField("exec_id", StringType, nullable = false),
    StructField("order_id", StringType, nullable = false),
    StructField("symbol", StringType, nullable = false),
    StructField("side", StringType, nullable = false),
    StructField("exec_type", StringType, nullable = false),
    StructField("quantity", LongType, nullable = false),
    StructField("price", DecimalType(18, 6), nullable = false),
    StructField("trade_date", DateType, nullable = false),
    StructField("counterparty", StringType, nullable = false),
    StructField("exchange", StringType, nullable = false),
    StructField("currency", StringType, nullable = false),
    StructField("notional", DecimalType(18, 2), nullable = false),
    StructField("settlement_date", DateType, nullable = false),
    StructField("booked_at", TimestampType, nullable = false)
  ))

  /**
   * Merge a batch of enriched trades into the Delta table.
   * Returns (inserted, updated, skippedDuplicates).
   *
   * Semantics:
   *   - New ExecID: insert.
   *   - Existing ExecID, identical quantity and price: skip (duplicate).
   *   - Existing ExecID, ExecType = TradeCorrect: update (amendment).
   *   - Existing ExecID, ExecType = Trade, different content: NOT merged.
   *     The caller must route these to the DLQ as data conflicts, because
   *     silently overwriting a booked trade destroys the audit trail.
   */
  def mergeBatch(
    spark: SparkSession,
    tablePath: String,
    trades: Seq[EnrichedTrade]
  ): (Long, Long, Long) = {
    import spark.implicits._

    if (trades.isEmpty) return (0L, 0L, 0L)

    val incoming = trades.map { e =>
      (
        e.trade.execId,
        e.trade.orderId,
        e.trade.symbol,
        e.trade.side.toString,
        e.trade.execType.toString,
        e.trade.quantity,
        e.trade.price,
        java.sql.Date.valueOf(e.trade.tradeDate),
        e.trade.counterparty,
        e.exchange,
        e.currency,
        e.notional,
        java.sql.Date.valueOf(e.settlementDate),
        new java.sql.Timestamp(System.currentTimeMillis())
      )
    }.toDF(
      "exec_id", "order_id", "symbol", "side", "exec_type", "quantity",
      "price", "trade_date", "counterparty", "exchange", "currency",
      "notional", "settlement_date", "booked_at"
    )

    val table = DeltaTable.forPath(spark, tablePath)

    // Split the batch: conflicts are detected before the merge, because
    // Delta's merge cannot express "update only if ExecType is G" and
    // "reject otherwise" in a single statement without losing the
    // distinction between a duplicate and a conflict.
    val existingIds = table.toDF
      .select("exec_id", "quantity", "price")
      .collect()
      .map(r => r.getString(0) -> (r.getLong(1), r.getDecimal(2)))
      .toMap

    val (conflicts, clean) = trades.partition { e =>
      existingIds.get(e.trade.execId) match {
        case Some((q, p)) =>
          e.trade.execType.toString != "TradeCorrect" &&
          (q != e.trade.quantity || BigDecimal(p.toString) != e.trade.price)
        case None => false
      }
    }

    if (conflicts.nonEmpty) {
      log.error("Data conflicts detected for ExecIDs: {} — routing to DLQ, not merging",
        conflicts.map(_.trade.execId).mkString(", "))
      // The caller handles conflicts; this method merges only clean rows.
      // Returning the conflict count in the skipped slot keeps the caller
      // honest about what was NOT booked.
      val (i, u, s) = mergeClean(spark, table, incoming, clean, existingIds)
      (i, u, s + conflicts.size)
    } else {
      mergeClean(spark, table, incoming, clean, existingIds)
    }
  }

  private def mergeClean(
    spark: SparkSession,
    table: DeltaTable,
    incoming: org.apache.spark.sql.DataFrame,
    clean: Seq[EnrichedTrade],
    existingIds: Map[String, (Long, java.math.BigDecimal)]
  ): (Long, Long, Long) = {
    import spark.implicits._

    val cleanIds = clean.map(_.trade.execId).toSet
    val cleanDf = incoming.filter(col("exec_id").isin(cleanIds.toSeq: _*))

    val duplicates = clean.count(e => existingIds.contains(e.trade.execId))
    val inserts = clean.size - duplicates

    table
      .as("t")
      .merge(cleanDf.as("s"), "t.exec_id = s.exec_id")
      .whenMatched("s.exec_type = 'TradeCorrect'")
      .updateAll()
      .whenNotMatched()
      .insertAll()
      .execute()

    // Delta does not return affected-row counts directly from merge in
    // this API version, so counts are derived from the batch composition.
    // A production implementation reads Delta's operation metrics instead.
    (inserts.toLong, (clean.size - inserts - duplicates).toLong, duplicates.toLong)
  }

  /** Create the table if it does not exist. Idempotent. */
  def ensureTable(spark: SparkSession, tablePath: String): Unit = {
    import spark.implicits._
    if (!DeltaTable.isDeltaTable(spark, tablePath)) {
      spark.createDataFrame(spark.sparkContext.emptyRDD[Row], schema)
        .write
        .format("delta")
        .mode("overwrite")
        .save(tablePath)
      log.info("Created Delta table at {}", tablePath)
    }
  }
}
