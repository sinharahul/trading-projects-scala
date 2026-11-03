package com.trading.surveillance.detectors

import com.trading.surveillance.model.Alert
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions.Window
import java.sql.Timestamp
import java.util.UUID

object UnusualVolumeDetector {
  val DetectorName = "UNUSUAL_VOLUME"

  /** Daily volume table schema: symbol, day, volume.
    * The detector computes a 30-day rolling mean and standard deviation
    * per symbol, excluding the current day, and scores the current day.
    */
  def detect(
    dailyVolumes: DataFrame,
    lookbackDays: Int = 30,
    zThreshold: Double = 3.0,
    minVolume: Long = 10000L
  )(implicit spark: SparkSession): DataFrame = {
    import spark.implicits._

    val w = Window
      .partitionBy(col("symbol"))
      .orderBy(col("day"))
      .rowsBetween(-lookbackDays, -1)

    val withStats = dailyVolumes
      .withColumn("mean30", avg(col("volume")).over(w))
      .withColumn("stddev30", stddev(col("volume")).over(w))
      .filter(col("mean30").isNotNull && col("stddev30").isNotNull)
      .withColumn("zScore",
        (col("volume") - col("mean30")) / col("stddev30"))

    val firing = withStats.filter(
      col("zScore") >= zThreshold && col("volume") >= minVolume
    )

    firing.map { row =>
      val symbol = row.getAs[String]("symbol")
      val day = row.getAs[java.sql.Date]("day")
      val volume = row.getAs[Long]("volume")
      val mean = row.getAs[Double]("mean30")
      val z = row.getAs[Double]("zScore")
      val windowStart = Timestamp.valueOf(day.toLocalDate.atStartOfDay())
      val windowEnd = Timestamp.valueOf(day.toLocalDate.atTime(23, 59, 59))
      val rawScore =
        if (z >= 6.0) 80
        else if (z >= 4.5) 68
        else 55
      Alert(
        alertId = UUID.randomUUID().toString,
        detector = DetectorName,
        symbol = symbol,
        accountIds = Seq.empty,
        beneficialOwnerIds = Seq.empty,
        windowStart = windowStart,
        windowEnd = windowEnd,
        evidence = f"Unusual volume in $symbol on $day: $volume shares " +
          f"vs 30-day mean $mean%.0f (z-score $z%.2f).",
        evidenceOrderIds = Seq.empty,
        evidenceTradeIds = Seq.empty,
        rawScore = rawScore,
        detectedAt = new Timestamp(System.currentTimeMillis())
      )
    }.toDF()
  }
}
