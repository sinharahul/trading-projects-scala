package com.backoffice.eod.jobs

import com.backoffice.eod.ledger._
import com.backoffice.eod.model._
import java.time.LocalDate
import org.apache.spark.sql.SparkSession

class PostLedgerJob(spark: SparkSession) extends EodJob {
  override def name: String = "post_ledger"
  override def dependencies: Seq[String] = Seq("compute_pnl")

  override def run(businessDate: LocalDate): Long = {
    import spark.implicits._
    val date = businessDate.toString
    val trades = spark.table("silver.silver_trades")
      .filter($"business_date" === date)
      .filter(!$"is_cancelled")
      .collect()
    // Cost basis of shares sold, per trade, recomputed by folding
    // in execution order (same fold as the positions job).
    val opening = Map.empty[(Long, Long), PositionState]
    val grouped = trades.groupBy(r =>
      (r.getAs[Long]("account_key"), r.getAs[Long]("security_key")))
    val lines = grouped.flatMap { case ((acc, _), ts) =>
      var state = opening.getOrElse((acc, 0L), PositionState(0, 0))
      ts.sortBy(_.getAs[java.sql.Timestamp]("execution_time").getTime)
        .flatMap { t =>
          val qty = t.getAs[Long]("quantity")
          val price = t.getAs[Long]("price_cents")
          val amount = math.abs(qty) * price
          val commission = 100L
          val fees = com.backoffice.eod.model.FeeSchedule.feesForTrade(amount)
          val side = t.getAs[String]("side")
          val (next, _) = PositionMath.applyTrade(state, side, qty, price)
          val result =
            if (side == "BUY") {
              state = next
              LedgerPoster.postBuy(date, acc, t.getAs[String]("trade_id"),
                amount, commission, fees)
            } else {
              // Split the sale into a closing portion (against any
              // long position) and a short-opening portion.
              val closedQty =
                if (state.quantity > 0) math.min(state.quantity, qty) else 0L
              val costBasis = closedQty * state.avgCostCents
              val shortQty = qty - closedQty
              val shortProceeds = shortQty * price
              state = next
              LedgerPoster.postSell(date, acc, t.getAs[String]("trade_id"),
                amount, costBasis, shortProceeds, commission, fees)
            }
          result
        }
    }.toSeq
    val pnl = spark.table("gold.fact_daily_pnl")
      .filter($"business_date" === date)
      .collect()
    val unrealizedLines = pnl.flatMap { r =>
      LedgerPoster.postUnrealized(date, r.getAs[Long]("account_key"),
        r.getAs[Long]("security_key"), r.getAs[Long]("unrealized_pnl_cents"))
    }
    val all = lines ++ unrealizedLines
    LedgerPoster.checkBalanced(all)
    val df = all.map(l => (l.businessDate, l.accountKey, l.entryId,
      l.accountCode, l.debitCents, l.creditCents, l.reference,
      l.narrative)).toDF("business_date", "account_key", "entry_id",
      "account_code", "debit_cents", "credit_cents", "reference", "narrative")
    df.write.format("delta").mode("overwrite")
      .option("replaceWhere", s"business_date = '$date'")
      .saveAsTable("gold.ledger_entries")
    df.count()
  }
}
