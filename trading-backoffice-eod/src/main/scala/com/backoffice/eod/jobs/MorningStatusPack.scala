package com.backoffice.eod.jobs

import java.time.LocalDate
import org.apache.spark.sql.{SparkSession, functions => F}

/** Builds the one-page morning status pack for a business date.
  * Writes a single text report to the reports directory and
  * prints it to the job log. The pack is evidence, not a
  * dashboard: it is generated once, from the gold tables, after
  * the DAG succeeds. */
class MorningStatusPack(spark: SparkSession) {
  def build(businessDate: LocalDate): String = {
    import spark.implicits._
    val date = businessDate.toString
    val history = spark.table("ops.eod_run_history")
      .filter($"business_date" === date)
      .orderBy($"started_at")
      .collect()
    val failed = history.filter(_.getAs[String]("status") != "SUCCEEDED")
    val header =
      if (failed.isEmpty) s"EOD $date — COMPLETE"
      else s"EOD $date — INCOMPLETE (${failed.length} jobs failed; " +
        "numbers below are from the last successful run and are STALE)"
    val trades = spark.table("gold.fact_trades")
      .filter($"business_date" === date)
      .filter(!$"is_cancelled")
    val tradeCount = trades.count()
    val totalValue = trades.agg(F.sum($"amount_cents")).collect()
      .head.getAs[Long](0)
    val pnl = spark.table("gold.fact_daily_pnl")
      .filter($"business_date" === date)
    val pnlTotals = pnl.agg(
      F.sum($"realized_pnl_cents").as("r"),
      F.sum($"unrealized_pnl_cents").as("u"),
      F.sum($"commission_cents").as("c"),
      F.sum($"fee_cents").as("f"),
      F.sum($"net_pnl_cents").as("n")).collect().head
    val ledger = spark.table("gold.ledger_entries")
      .filter($"business_date" === date)
    val ledgerTotals = ledger.agg(
      F.sum($"debit_cents").as("dr"),
      F.sum($"credit_cents").as("cr")).collect().head
    val cancelledCount = spark.table("silver.silver_trades_cancelled")
      .filter($"business_date" === date).count()
    val topGainers = pnl
      .join(spark.table("gold.dim_account"), "account_key")
      .join(spark.table("gold.dim_security"), "security_key")
      .orderBy(F.desc("net_pnl_cents")).limit(5)
      .select($"account_number", $"ticker", $"net_pnl_cents")
      .collect()
      .map(r => s"  ${r.getString(0)} ${r.getString(1)} " +
        s"${r.getAs[Long]("net_pnl_cents")} cents").mkString("\n")
    s"""$header
       |
       |Run status:
       |${history.map(r =>
         s"  ${r.getAs[String]("job_name")}: " +
         s"${r.getAs[String]("status")} " +
         s"(${r.getAs[Long]("rows_processed")} rows)").mkString("\n")}
       |
       |Headline totals:
       |  Trades: $tradeCount, total value: $totalValue cents
       |  Realized P&L: ${pnlTotals.getAs[Long]("r")} cents
       |  Unrealized change: ${pnlTotals.getAs[Long]("u")} cents
       |  Commissions: ${pnlTotals.getAs[Long]("c")} cents
       |  Fees: ${pnlTotals.getAs[Long]("f")} cents
       |  Net P&L: ${pnlTotals.getAs[Long]("n")} cents
       |
       |Ledger balance proof:
       |  Debits: ${ledgerTotals.getAs[Long]("dr")} cents
       |  Credits: ${ledgerTotals.getAs[Long]("cr")} cents
       |  Difference: ${ledgerTotals.getAs[Long]("dr") -
         ledgerTotals.getAs[Long]("cr")} cents (must be 0)
       |
       |Exceptions:
       |  Cancelled trades: $cancelledCount (see silver_trades_cancelled)
       |
       |Top gainers by net P&L:
       |$topGainers
       |""".stripMargin
  }
}
