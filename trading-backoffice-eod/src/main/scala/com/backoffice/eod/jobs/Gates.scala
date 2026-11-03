package com.backoffice.eod.jobs

import com.backoffice.eod.model._
import java.time.LocalDate
import org.apache.spark.sql.{SparkSession, functions => F}

class PositionsGate(spark: SparkSession, dep: String) extends Gate {
  override def name: String = "positions_gate"
  override def dependencies: Seq[String] = Seq(dep)
  override def check(businessDate: LocalDate): Long = {
    import spark.implicits._
    val date = businessDate.toString
    val positions = spark.table("gold.fact_positions")
      .filter($"business_date" === date)
    val count = positions.count()
    if (count == 0)
      throw new PermanentFailure(
        s"Positions gate failed: no positions for $date")
    val nullKeys = positions
      .filter($"account_key".isNull || $"security_key".isNull).count()
    if (nullKeys > 0)
      throw new PermanentFailure(
        s"Positions gate failed: $nullKeys rows with null keys")
    val missingPrices = positions.filter($"market_price_cents" <= 0).count()
    if (missingPrices > 0)
      throw new PermanentFailure(
        s"Positions gate failed: $missingPrices positions with " +
          "non-positive market price")
    // Completeness: every traded account-security must appear.
    val traded = spark.table("silver.silver_trades")
      .filter($"business_date" === date)
      .filter(!$"is_cancelled")
      .select($"account_key", $"security_key").distinct()
    val snapKeys = positions.select($"account_key", $"security_key").distinct()
    val missing = traded.except(snapKeys).count()
    if (missing > 0)
      throw new PermanentFailure(
        s"Positions gate failed: $missing traded pairs missing " +
          "from the snapshot")
    count
  }
}

class PnlGate(spark: SparkSession, dep: String) extends Gate {
  override def name: String = "pnl_gate"
  override def dependencies: Seq[String] = Seq(dep)
  override def check(businessDate: LocalDate): Long = {
    import spark.implicits._
    val date = businessDate.toString
    val pnl = spark.table("gold.fact_daily_pnl")
      .filter($"business_date" === date)
    val badTie = pnl.filter(
      $"net_pnl_cents" =!=
        ($"realized_pnl_cents" + $"unrealized_pnl_cents" -
          $"commission_cents" - $"fee_cents")).count()
    if (badTie > 0)
      throw new PermanentFailure(
        s"P&L gate failed: $badTie rows violate the tie-out identity")
    pnl.count()
  }
}

class LedgerBalanceGate(spark: SparkSession, dep: String) extends Gate {
  override def name: String = "ledger_gate"
  override def dependencies: Seq[String] = Seq(dep)
  override def check(businessDate: LocalDate): Long = {
    import spark.implicits._
    val date = businessDate.toString
    val ledger = spark.table("gold.ledger_entries")
      .filter($"business_date" === date)
    val sums = ledger.agg(
      F.sum($"debit_cents").as("dr"),
      F.sum($"credit_cents").as("cr")).collect().head
    val dr = sums.getAs[Long]("dr")
    val cr = sums.getAs[Long]("cr")
    if (dr != cr)
      throw new PermanentFailure(
        s"Ledger gate failed: debits $dr != credits $cr")
    val perAccount = ledger.groupBy($"account_key")
      .agg((F.sum($"debit_cents") - F.sum($"credit_cents")).as("diff"))
      .filter($"diff" =!= 0).count()
    if (perAccount > 0)
      throw new PermanentFailure(
        s"Ledger gate failed: $perAccount accounts out of balance")
    ledger.count()
  }
}

class AggregatesGate(spark: SparkSession, dep: String) extends Gate {
  override def name: String = "aggregates_gate"
  override def dependencies: Seq[String] = Seq(dep)
  override def check(businessDate: LocalDate): Long = {
    import spark.implicits._
    val date = businessDate.toString
    // Aggregates must tie to facts: total volume value must match
    // the sum of trade amounts, and desk net must match P&L net.
    val factValue = spark.table("gold.fact_trades")
      .filter($"business_date" === date)
      .filter(!$"is_cancelled")
      .agg(F.sum($"amount_cents").as("v")).collect().head.getAs[Long]("v")
    val aggValue = spark.table("gold.agg_daily_volume")
      .filter($"business_date" === date)
      .agg(F.sum($"total_value_cents").as("v")).collect().head.getAs[Long]("v")
    if (factValue != aggValue)
      throw new PermanentFailure(
        s"Aggregates gate failed: volume $aggValue != fact value $factValue")
    val pnlNet = spark.table("gold.fact_daily_pnl")
      .filter($"business_date" === date)
      .agg(F.sum($"net_pnl_cents").as("v")).collect().head.getAs[Long]("v")
    val deskNet = spark.table("gold.agg_desk_pnl")
      .filter($"business_date" === date)
      .agg(F.sum($"net_pnl_cents").as("v")).collect().head.getAs[Long]("v")
    if (pnlNet != deskNet)
      throw new PermanentFailure(
        s"Aggregates gate failed: desk net $deskNet != P&L net $pnlNet")
    2L
  }
}
