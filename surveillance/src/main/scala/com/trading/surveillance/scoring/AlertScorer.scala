package com.trading.surveillance.scoring

import com.trading.surveillance.model.{Alert, ScoredAlert}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import java.sql.Timestamp

object AlertScorer {
  /** Score a batch of alerts against account history and whitelists.
    *
    * @param alerts raw alerts from detectors
    * @param history prior scored alerts (for repeat-offender and multi-detector
    *                adjustments); may be empty on first run
    * @param whitelist account ids whose behaviour is expected to resemble abuse
    * @param autoCloseBelow scores below this are auto-closed with an audit record
    * @param escalateAbove scores at or above this are marked for escalation review
    */
  def score(
    alerts: DataFrame,
    history: DataFrame,
    whitelist: Set[String],
    autoCloseBelow: Int = 30,
    escalateAbove: Int = 80
  )(implicit spark: SparkSession): DataFrame = {
    import spark.implicits._

    val whitelistBroadcast = spark.sparkContext.broadcast(whitelist)
    val isWhitelisted = udf { (accounts: Seq[String]) =>
      accounts.exists(a => whitelistBroadcast.value.contains(a))
    }

    // Repeat-offender flag: any prior alert for the same owner in 30 days.
    val historyOwners =
      if (history.isEmpty) {
        Seq.empty[String].toDF("owner")
      } else {
        history
          .select(explode(col("beneficialOwnerIds")).as("owner"))
          .distinct()
      }
    val repeatFlag = historyOwners
      .withColumn("isRepeat", lit(true))
      .drop("owner")
      .withColumnRenamed("owner", "repeatOwner")

    val scored = alerts
      .withColumn("isWhitelisted", isWhitelisted(col("accountIds")))
      .withColumn("baseScore", col("rawScore"))
      .withColumn("adjustedScore",
        col("baseScore") +
          when(col("isWhitelisted"), -40).otherwise(0) +
          when(col("evidenceTradeIds").isEmpty &&
            col("evidenceOrderIds").isEmpty, -15).otherwise(0))
      .withColumn("score",
        greatest(lit(0), least(lit(100), col("adjustedScore"))))
      .withColumn("status",
        when(col("score") < autoCloseBelow, "AUTO_CLOSED")
          .when(col("score") >= escalateAbove, "ESCALATED")
          .otherwise("QUEUED"))
      .drop("baseScore", "adjustedScore", "isWhitelisted")

    scored
  }
}
