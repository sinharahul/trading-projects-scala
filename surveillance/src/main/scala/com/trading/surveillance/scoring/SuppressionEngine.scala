package com.trading.surveillance.scoring

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions.Window

object SuppressionEngine {
  /** Suppress duplicate and cooldown alerts in a batch.
    *
    * @param scored alerts with score and status from AlertScorer
    * @param priorQueued alerts already queued in the last cooldown period,
    *                    with columns detector, symbol, ownerKey
    * @param cooldownHours how long a queued alert suppresses repeats
    * @return alerts with status updated to SUPPRESSED where applicable, and a
    *         suppressionReason column
    */
  def suppress(
    scored: DataFrame,
    priorQueued: DataFrame,
    cooldownHours: Int = 24
  )(implicit spark: SparkSession): DataFrame = {
    import spark.implicits._

    val withKey = scored
      .withColumn("ownerKey",
        concat_ws("|", col("detector"), col("symbol"),
          concat_ws(",", sort_array(col("beneficialOwnerIds")))))
      .withColumn("evidenceKey",
        concat_ws("|",
          concat_ws(",", sort_array(col("evidenceTradeIds"))),
          concat_ws(",", sort_array(col("evidenceOrderIds")))))

    // Deduplication within the batch: keep the highest score per evidence key.
    val rankWindow = Window
      .partitionBy(col("detector"), col("symbol"), col("evidenceKey"))
      .orderBy(col("score").desc)
    val deduped = withKey
      .withColumn("rank", row_number().over(rankWindow))
      .withColumn("status",
        when(col("rank") > 1 && col("evidenceKey") =!= "|", "SUPPRESSED")
          .otherwise(col("status")))
      .withColumn("suppressionReason",
        when(col("rank") > 1 && col("evidenceKey") =!= "|", "DUPLICATE")
          .otherwise(lit(null).cast("string")))

    // Cooldown against previously queued alerts.
    val priorKeys = priorQueued
      .select(col("ownerKey"))
      .distinct()
      .withColumn("inCooldown", lit(true))

    deduped
      .join(priorKeys, Seq("ownerKey"), "left")
      .withColumn("status",
        when(col("inCooldown").isNotNull && col("status") === "QUEUED",
          "SUPPRESSED").otherwise(col("status")))
      .withColumn("suppressionReason",
        when(col("inCooldown").isNotNull && col("suppressionReason").isNull,
          "COOLDOWN").otherwise(col("suppressionReason")))
      .drop("rank", "inCooldown", "ownerKey", "evidenceKey")
  }
}
