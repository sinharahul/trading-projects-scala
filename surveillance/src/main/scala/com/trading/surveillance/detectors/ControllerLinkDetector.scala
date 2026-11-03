package com.trading.surveillance.detectors

import com.trading.surveillance.model.Alert
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import java.sql.Timestamp
import java.util.UUID

object ControllerLinkDetector {
  val DetectorName = "WASH_TRADE_CONTROLLER_LINK"

  /** Detect wash trades through a shared controller rather than a shared owner.
    *
    * @param trades trades with the schema from Schemas.tradeSchema
    * @param orders order events, used to recover controller ids and device
    *               fingerprints for the orders behind each trade
    * @param windowMinutes sliding window for the repetition test
    * @param minRepetitions minimum linked trades in the window to fire
    */
  def detect(
    trades: DataFrame,
    orders: DataFrame,
    windowMinutes: Int = 5,
    minRepetitions: Int = 3
  )(implicit spark: SparkSession): DataFrame = {
    import spark.implicits._

    // Map each order id to its controller and device fingerprint.
    val orderMeta = orders
      .filter(col("eventType") === "PLACED")
      .select(
        col("orderId"),
        col("controllerId").as("orderController"),
        col("deviceFingerprint").as("orderDevice")
      )
      .dropDuplicates("orderId")

    // Attach controller metadata to both sides of each trade.
    val enriched = trades
      .join(orderMeta.as("buy"),
        col("buyOrderId") === col("buy.orderId"), "left")
      .join(orderMeta.as("sell"),
        col("sellOrderId") === col("sell.orderId"), "left")
      .select(
        col("tradeId"), col("symbol"), col("quantity"), col("tradeTime"),
        col("buyBeneficialOwnerId"), col("sellBeneficialOwnerId"),
        col("buy.orderController").as("buyController"),
        col("sell.orderController").as("sellController"),
        col("buy.orderDevice").as("buyDevice"),
        col("sell.orderDevice").as("sellDevice")
      )

    // Same controller, different owners, shared device fingerprint.
    val linked = enriched.filter(
      col("buyController").isNotNull &&
      col("sellController").isNotNull &&
      col("buyController") === col("sellController") &&
      col("buyBeneficialOwnerId") =!= col("sellBeneficialOwnerId") &&
      col("buyDevice").isNotNull &&
      col("buyDevice") === col("sellDevice")
    )

    val windowed = linked
      .withColumn(
        "window",
        window(col("tradeTime"), s"$windowMinutes minutes", "1 minute")
      )
      .groupBy(col("window"), col("buyController").as("controller"),
        col("symbol"))
      .agg(
        count("*").as("tradeCount"),
        sum("quantity").as("totalQty"),
        collect_list("tradeId").as("tradeIds"),
        min("tradeTime").as("windowStart"),
        max("tradeTime").as("windowEnd")
      )
      .filter(col("tradeCount") >= minRepetitions)

    windowed.map { row =>
      val controller = row.getAs[String]("controller")
      val symbol = row.getAs[String]("symbol")
      val tradeCount = row.getAs[Long]("tradeCount")
      val totalQty = row.getAs[Long]("totalQty")
      val tradeIds = row.getAs[Seq[String]]("tradeIds")
      Alert(
        alertId = UUID.randomUUID().toString,
        detector = DetectorName,
        symbol = symbol,
        accountIds = Seq.empty,
        beneficialOwnerIds = Seq.empty,
        windowStart = row.getAs[Timestamp]("windowStart"),
        windowEnd = row.getAs[Timestamp]("windowEnd"),
        evidence = s"Controller-link wash pattern: $tradeCount trades, " +
          s"$totalQty shares, same controller $controller on both sides " +
          s"through different owners in $symbol, sharing a device fingerprint.",
        evidenceOrderIds = Seq.empty,
        evidenceTradeIds = tradeIds,
        rawScore = if (tradeCount >= 5) 70 else 55,
        detectedAt = new Timestamp(System.currentTimeMillis())
      )
    }.toDF()
  }
}
