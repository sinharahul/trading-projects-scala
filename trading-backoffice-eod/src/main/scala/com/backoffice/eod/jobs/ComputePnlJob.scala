package com.backoffice.eod.jobs

import com.backoffice.eod.model._
import java.time.LocalDate
import org.apache.spark.sql.{SparkSession, functions => F}

class ComputePnlJob(spark: SparkSession) extends EodJob {
  override def name: String = "compute_pnl"
  override def dependencies: Seq[String] = Seq("finalize_positions")

  override def run(businessDate: LocalDate): Long = {
    import spark.implicits._
    val prevDate = spark.sql(
      s"""SELECT prev_business_date FROM gold.dim_date
         |WHERE calendar_date = DATE '$businessDate'""".stripMargin)
      .collect().head.getDate(0).toLocalDate

    val today = spark.table("gold.fact_positions")
      .filter($"business_date" === businessDate.toString)
      .collect()
    val yesterday = spark.table("gold.fact_positions")
      .filter($"business_date" === prevDate.toString)
      .collect()
      .map(r => ((r.getAs[Long]("account_key"), r.getAs[Long]("security_key")),
        r.getAs[Long]("unrealized_pnl_cents")))
      .toMap

    val trades = spark.table("silver.silver_trades")
      .filter($"business_date" === businessDate.toString)
      .filter(!$"is_cancelled")
      .collect()

    // Realized P&L recomputed independently from the trade list:
    // fold each account-security group from its opening state.
    val opening = spark.table("gold.fact_positions")
      .filter($"business_date" === prevDate.toString)
      .collect()
      .map(r => ((r.getAs[Long]("account_key"), r.getAs[Long]("security_key")),
        PositionState(r.getAs[Long]("quantity"), r.getAs[Long]("avg_cost_cents"))))
      .toMap
    val grouped = trades.groupBy(r =>
      (r.getAs[Long]("account_key"), r.getAs[Long]("security_key")))
    val realizedByKey = grouped.map { case (key, ts) =>
      var state = opening.getOrElse(key, PositionState(0, 0))
      var realized = 0L
      ts.sortBy(_.getAs[java.sql.Timestamp]("execution_time").getTime)
        .foreach { t =>
          val (next, r) = PositionMath.applyTrade(state,
            t.getAs[String]("side"), t.getAs[Long]("quantity"),
            t.getAs[Long]("price_cents"))
          state = next
          realized += r
        }
      (key, realized)
    }
    val tradeStats = grouped.map { case (key, ts) =>
      val amount = ts.map(t =>
        math.abs(t.getAs[Long]("quantity")) * t.getAs[Long]("price_cents")).sum
      val fees = ts.map(t =>
        FeeSchedule.feesForTrade(
          math.abs(t.getAs[Long]("quantity")) * t.getAs[Long]("price_cents"))).sum
      (key, (ts.size.toLong, amount, fees))
    }

    val rows = today.map { r =>
      val acc = r.getAs[Long]("account_key")
      val sec = r.getAs[Long]("security_key")
      val key = (acc, sec)
      val unrealizedToday = r.getAs[Long]("unrealized_pnl_cents")
      val unrealizedYesterday = yesterday.getOrElse(key, 0L)
      val unrealizedChange = unrealizedToday - unrealizedYesterday
      val realized = realizedByKey.getOrElse(key, 0L)
      val (count, _, fees) = tradeStats.getOrElse(key, (0L, 0L, 0L))
      val commission = FeeSchedule.commissionForTradeCount(count)
      val net = realized + unrealizedChange - commission - fees
      (businessDate.toString, acc, sec, realized, unrealizedChange,
        commission, fees, net)
    }
    val df = rows.toDF("business_date", "account_key", "security_key",
      "realized_pnl_cents", "unrealized_pnl_cents", "commission_cents",
      "fee_cents", "net_pnl_cents")
    df.write.format("delta").mode("overwrite")
      .option("replaceWhere", s"business_date = '$businessDate'")
      .saveAsTable("gold.fact_daily_pnl")
    df.count()
  }
}
