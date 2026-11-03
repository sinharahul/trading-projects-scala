package com.trading.surveillance.detectors

import com.trading.surveillance.model.Alert
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import java.sql.Timestamp
import java.util.UUID

object MarkingCloseDetector {
  val DetectorName = "MARKING_CLOSE"

  /** Detect marking-the-close patterns in a batch of trades.
    *
    * @param trades trades with the schema from Schemas.tradeSchema
    * @param closeWindowMinutes minutes before the 16:00 close that count
    * @param minVolumeSharePct minimum close-window share of daily volume
    * @param minPriceMovePct minimum price move across the close window
    * @param minAccountSharePct minimum share of close-window volume by one owner
    */
  def detect(
    trades: DataFrame,
    closeWindowMinutes: Int = 15,
    minVolumeSharePct: Double = 20.0,
    minPriceMovePct: Double = 1.0,
    minAccountSharePct: Double = 50.0
  )(implicit spark: SparkSession): DataFrame = {
    import spark.implicits._

    val withDay = trades
      .withColumn("day", to_date(col("tradeTime")))
      .withColumn("minute", minute(col("tradeTime")))
      .withColumn("hour", hour(col("tradeTime")))
      // Close window: 15:45–16:00.
      .withColumn("inCloseWindow",
        (col("hour") === 15 && col("minute") >= (60 - closeWindowMinutes)) ||
        (col("hour") === 16 && col("minute") === 0))

    // Daily totals per symbol.
    val daily = withDay
      .groupBy(col("day"), col("symbol"))
      .agg(sum("quantity").as("dailyVolume"))

    // Close-window aggregates per symbol and day.
    val closeAgg = withDay
      .filter(col("inCloseWindow"))
      .groupBy(col("day"), col("symbol"))
      .agg(
        sum("quantity").as("closeVolume"),
        sum(col("price") * col("quantity")).as("closeNotional"),
        min("tradeTime").as("windowStart"),
        max("tradeTime").as("windowEnd"),
        min("price").as("firstPrice"),
        max("price").as("lastPrice")
      )

    // Per-owner share of close-window volume.
    val ownerShare = withDay
      .filter(col("inCloseWindow"))
      .groupBy(col("day"), col("symbol"), col("buyBeneficialOwnerId"))
      .agg(sum("quantity").as("ownerBuyVolume"))
      .groupBy(col("day"), col("symbol"))
      .agg(max("ownerBuyVolume").as("topOwnerVolume"))

    // Sell-side concentration as well: marking can be done by selling.
    val ownerSellShare = withDay
      .filter(col("inCloseWindow"))
      .groupBy(col("day"), col("symbol"), col("sellBeneficialOwnerId"))
      .agg(sum("quantity").as("ownerSellVolume"))
      .groupBy(col("day"), col("symbol"))
      .agg(max("ownerSellVolume").as("topSellerVolume"))

    val joined = closeAgg
      .join(daily, Seq("day", "symbol"))
      .join(ownerShare, Seq("day", "symbol"), "left")
      .join(ownerSellShare, Seq("day", "symbol"), "left")
      .withColumn("volumeSharePct", col("closeVolume") / col("dailyVolume") * 100)
      .withColumn("topSharePct",
        greatest(col("topOwnerVolume"), col("topSellerVolume")) /
          col("closeVolume") * 100)
      .withColumn("priceMovePct",
        (col("lastPrice") - col("firstPrice")) / col("firstPrice") * 100)

    val firing = joined.filter(
      col("volumeSharePct") >= minVolumeSharePct &&
      abs(col("priceMovePct")) >= minPriceMovePct &&
      col("topSharePct") >= minAccountSharePct
    )

    firing.map { row =>
      val symbol = row.getAs[String]("symbol")
      val windowStart = row.getAs[Timestamp]("windowStart")
      val windowEnd = row.getAs[Timestamp]("windowEnd")
      val volumeShare = row.getAs[Double]("volumeSharePct")
      val priceMove = row.getAs[Double]("priceMovePct")
      val topShare = row.getAs[Double]("topSharePct")
      val rawScore =
        if (volumeShare >= 40 && math.abs(priceMove) >= 2.0) 88
        else if (volumeShare >= 30) 76
        else 65
      Alert(
        alertId = UUID.randomUUID().toString,
        detector = DetectorName,
        symbol = symbol,
        accountIds = Seq.empty,
        beneficialOwnerIds = Seq.empty,
        windowStart = windowStart,
        windowEnd = windowEnd,
        evidence = f"Marking-the-close pattern in $symbol: close-window volume " +
          f"is $volumeShare%.1f%% of daily volume, price moved $priceMove%.2f%%, " +
          f"top owner share $topShare%.1f%% of close-window volume.",
        evidenceOrderIds = Seq.empty,
        evidenceTradeIds = Seq.empty,
        rawScore = rawScore,
        detectedAt = new Timestamp(System.currentTimeMillis())
      )
    }.toDF()
  }
}
