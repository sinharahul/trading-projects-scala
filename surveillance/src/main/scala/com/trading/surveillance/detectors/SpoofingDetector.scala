package com.trading.surveillance.detectors

import com.trading.surveillance.model.Alert
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import java.sql.Timestamp
import java.util.UUID

object SpoofingDetector {
  val DetectorName = "SPOOFING"

  /** Collapse order events into one lifecycle row per order.
    *
    * @return columns: orderId, accountId, beneficialOwnerId, symbol, side,
    *         quantity, price, placedAt, cancelledAt, filledQty, lifetimeSeconds
    */
  def lifecycles(orders: DataFrame): DataFrame = {
    orders
      .groupBy(
        col("orderId"), col("accountId"), col("beneficialOwnerId"),
        col("symbol"), col("side"), col("quantity"), col("price")
      )
      .agg(
        min(when(col("eventType") === "PLACED", col("eventTime")))
          .as("placedAt"),
        min(when(col("eventType") === "CANCELLED", col("eventTime")))
          .as("cancelledAt"),
        max(when(col("eventType") === "FILLED", col("quantity"))
          .otherwise(lit(0L))).as("filledQty"),
        count(when(col("eventType") === "FILLED", 1)).as("fillEvents")
      )
      .withColumn(
        "lifetimeSeconds",
        when(col("cancelledAt").isNotNull,
          unix_timestamp(col("cancelledAt")) - unix_timestamp(col("placedAt")))
          .otherwise(lit(null).cast("long"))
      )
  }

  /** Detect spoofing patterns in a batch of order events.
    *
    * @param orders order events with the schema from Schemas.orderEventSchema
    * @param vwapBySymbol map of symbol to the day's volume-weighted average
    *                     price, used as the top-of-book proxy
    * @param minOrderSize minimum quantity for a spoof candidate
    * @param cancelWithinSeconds maximum lifetime for a spoof candidate
    * @param minRepetitions minimum candidates per (account, symbol, hour)
    */
  def detect(
    orders: DataFrame,
    vwapBySymbol: Map[String, Double],
    minOrderSize: Long = 10000L,
    cancelWithinSeconds: Long = 10L,
    minRepetitions: Int = 5
  )(implicit spark: SparkSession): DataFrame = {
    import spark.implicits._

    val life = lifecycles(orders)

    // Broadcast the small VWAP map for the price-proximity test.
    val vwap = spark.sparkContext.broadcast(vwapBySymbol)
    val nearBook = udf { (symbol: String, price: Double) =>
      vwap.value.get(symbol) match {
        case Some(v) if v > 0 => math.abs(price - v) / v <= 0.01
        case _ => false
      }
    }

    // Spoof candidates: large, near book, cancelled fast, never filled.
    val candidates = life.filter(
      col("quantity") >= minOrderSize &&
      col("cancelledAt").isNotNull &&
      col("lifetimeSeconds") <= cancelWithinSeconds &&
      col("filledQty") === 0L &&
      nearBook(col("symbol"), col("price"))
    )

    // Fill ratio per (account, symbol): filled large orders / all large orders.
    val largeOrders = life.filter(col("quantity") >= minOrderSize)
    val fillRatio = largeOrders
      .groupBy(col("accountId"), col("symbol"))
      .agg(
        (sum(when(col("filledQty") > 0, 1).otherwise(0)).cast("double") /
          count("*")).as("fillRatio")
      )

    // Repetition per (account, symbol, hour).
    val grouped = candidates
      .withColumn("hour", date_trunc("hour", col("placedAt")))
      .groupBy(col("hour"), col("accountId"), col("beneficialOwnerId"), col("symbol"))
      .agg(
        count("*").as("candidateCount"),
        collect_list("orderId").as("orderIds"),
        min("placedAt").as("windowStart"),
        max("placedAt").as("windowEnd")
      )
      .join(fillRatio, Seq("accountId", "symbol"), "left")
      .filter(
        col("candidateCount") >= minRepetitions &&
        col("fillRatio") < 0.05
      )

    grouped.map { row =>
      val account = row.getAs[String]("accountId")
      val owner = row.getAs[String]("beneficialOwnerId")
      val symbol = row.getAs[String]("symbol")
      val count = row.getAs[Long]("candidateCount")
      val orderIds = row.getAs[Seq[String]]("orderIds")
      val windowStart = row.getAs[Timestamp]("windowStart")
      val windowEnd = row.getAs[Timestamp]("windowEnd")
      val fillRatioVal = row.getAs[Double]("fillRatio")
      val rawScore =
        if (count >= 20) 92
        else if (count >= 10) 82
        else 70
      Alert(
        alertId = UUID.randomUUID().toString,
        detector = DetectorName,
        symbol = symbol,
        accountIds = Seq(account),
        beneficialOwnerIds = Seq(owner),
        windowStart = windowStart,
        windowEnd = windowEnd,
        evidence = f"Spoofing pattern: $count large orders (min $minOrderSize shares) " +
          f"placed near the top of book and cancelled within $cancelWithinSeconds seconds " +
          f"without execution in $symbol; large-order fill ratio ${fillRatioVal * 100}%.1f%%.",
        evidenceOrderIds = orderIds,
        evidenceTradeIds = Seq.empty,
        rawScore = rawScore,
        detectedAt = new Timestamp(System.currentTimeMillis())
      )
    }.toDF()
  }
}
