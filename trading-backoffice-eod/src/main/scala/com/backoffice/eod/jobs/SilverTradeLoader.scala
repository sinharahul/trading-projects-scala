package com.backoffice.eod.jobs

import java.time.LocalDate
import org.apache.spark.sql.{SparkSession, functions => F}

/** Resolves bronze trade reports into final silver trades.
  *
  * Bronze grain: one row per execution report received.
  * Silver grain: one row per final trade (corrections applied),
  * plus a separate audit table for cancelled trades.
  */
class SilverTradeLoader(spark: SparkSession) {
  def run(businessDate: LocalDate): Long = {
    import spark.implicits._
    val date = businessDate.toString
    val bronze = spark.table("bronze.bronze_trades")
      .filter($"business_date" === date)
      .orderBy($"receipt_seq")

    // Validate: every correction must reference a known trade id.
    val ids = bronze.select($"trade_id").distinct()
      .collect().map(_.getString(0)).toSet
    val badCorrections = bronze
      .filter($"correction_of".isNotNull)
      .filter(!$"correction_of".isin(ids.toSeq: _*))
      .count()
    if (badCorrections > 0)
      throw new com.backoffice.eod.model.PermanentFailure(
        s"Silver loader: $badCorrections corrections reference unknown trades")

    // Latest row per trade id wins, by receipt sequence.
    val window = org.apache.spark.sql.expressions.Window
      .partitionBy($"trade_id").orderBy(F.desc("receipt_seq"))
    val latest = bronze
      .withColumn("rn", F.row_number().over(window))
      .filter($"rn" === 1).drop("rn")

    val cancelled = latest.filter($"cancelled" === true)
    val finals = latest.filter($"cancelled" === false)

    // Audit table for cancelled trades: never silently drop data.
    cancelled
      .select($"trade_id", $"account_key", $"security_key", $"business_date")
      .write.format("delta").mode("overwrite")
      .option("replaceWhere", s"business_date = '$date'")
      .saveAsTable("silver.silver_trades_cancelled")

    finals.write.format("delta").mode("overwrite")
      .option("replaceWhere", s"business_date = '$date'")
      .saveAsTable("silver.silver_trades")
    finals.count()
  }
}
