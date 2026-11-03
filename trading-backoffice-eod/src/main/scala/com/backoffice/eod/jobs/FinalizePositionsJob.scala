package com.backoffice.eod.jobs

import com.backoffice.eod.model._
import java.time.LocalDate
import org.apache.spark.sql.{SparkSession, functions => F}

class FinalizePositionsJob(spark: SparkSession) extends EodJob {
  override def name: String = "finalize_positions"

  override def run(businessDate: LocalDate): Long = {
    import spark.implicits._
    val trades = spark.table("silver.silver_trades")
      .filter($"business_date" === businessDate.toString)
      .filter(!$"is_cancelled")
      .orderBy($"account_key", $"security_key", $"execution_time")
      .collect()
    val prevDate = spark.sql(
      s"""SELECT prev_business_date FROM gold.dim_date
         |WHERE calendar_date = DATE '$businessDate'""".stripMargin)
      .collect().head.getDate(0).toLocalDate
    val opening = spark.table("gold.fact_positions")
      .filter($"business_date" === prevDate.toString)
      .collect()
      .map(r => ((r.getAs[Long]("account_key"), r.getAs[Long]("security_key")),
        PositionState(r.getAs[Long]("quantity"), r.getAs[Long]("avg_cost_cents"))))
      .toMap
    val splits = spark.table("silver.silver_corporate_actions")
      .filter($"effective_date" === businessDate.toString)
      .filter($"action_type" === "SPLIT")
      .collect()
      .map(r => (r.getAs[Long]("security_key"), r.getAs[Long]("parameter")))
      .toMap
    val prices = spark.table("silver.silver_prices")
      .filter($"business_date" === businessDate.toString)
      .collect()
      .map(r => (r.getAs[Long]("security_key"), r.getAs[Long]("close_cents")))
      .toMap

    // Fold trades per (account, security) in execution order.
    val grouped = trades.groupBy(r =>
      (r.getAs[Long]("account_key"), r.getAs[Long]("security_key")))
    val rows = grouped.flatMap { case ((acc, sec), ts) =>
      var state = opening.getOrElse((acc, sec), PositionState(0, 0))
      ts.foreach { t =>
        val (next, _) = PositionMath.applyTrade(state,
          t.getAs[String]("side"), t.getAs[Long]("quantity"),
          t.getAs[Long]("price_cents"))
        state = next
      }
      splits.get(sec).foreach(f => state = PositionMath.applySplit(state, f))
      val price = prices.getOrElse(sec,
        throw new PermanentFailure(
          s"Missing closing price for security_key $sec on $businessDate"))
      val marketValue = state.quantity * price
      val unrealized = marketValue - state.quantity * state.avgCostCents
      Seq((businessDate.toString, acc, sec, state.quantity,
        state.avgCostCents, price, marketValue, unrealized))
    }.toSeq
    val df = rows.toDF("business_date", "account_key", "security_key",
      "quantity", "avg_cost_cents", "market_price_cents",
      "market_value_cents", "unrealized_pnl_cents")
    df.write.format("delta").mode("overwrite")
      .option("replaceWhere", s"business_date = '$businessDate'")
      .saveAsTable("gold.fact_positions")
    df.count()
  }
}
