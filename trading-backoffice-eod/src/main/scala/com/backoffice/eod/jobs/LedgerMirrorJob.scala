package com.backoffice.eod.jobs

import java.sql.DriverManager
import java.time.LocalDate
import org.apache.spark.sql.SparkSession

/** Mirrors one business date of the gold ledger into Postgres.
  * Runs only after the ledger gate passes. Idempotent: the
  * date's rows are replaced inside a single transaction. */
class LedgerMirrorJob(spark: SparkSession, jdbcUrl: String,
  user: String, password: String) {
  def run(businessDate: LocalDate): Long = {
    import spark.implicits._
    val date = businessDate.toString
    val rows = spark.table("gold.ledger_entries")
      .filter($"business_date" === date)
      .collect()
    val conn = DriverManager.getConnection(jdbcUrl, user, password)
    try {
      conn.setAutoCommit(false)
      val del = conn.prepareStatement(
        "DELETE FROM ledger_entries WHERE business_date = ?")
      del.setDate(1, java.sql.Date.valueOf(businessDate))
      del.executeUpdate()
      val ins = conn.prepareStatement(
        """INSERT INTO ledger_entries
          |(business_date, account_key, entry_id, account_code,
          | debit_cents, credit_cents, reference, narrative)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin)
      rows.foreach { r =>
        ins.setDate(1, java.sql.Date.valueOf(businessDate))
        ins.setLong(2, r.getAs[Long]("account_key"))
        ins.setString(3, r.getAs[String]("entry_id"))
        ins.setString(4, r.getAs[String]("account_code"))
        ins.setLong(5, r.getAs[Long]("debit_cents"))
        ins.setLong(6, r.getAs[Long]("credit_cents"))
        ins.setString(7, r.getAs[String]("reference"))
        ins.setString(8, r.getAs[String]("narrative"))
        ins.addBatch()
      }
      ins.executeBatch()
      conn.commit()
      rows.length.toLong
    } catch {
      case e: Exception =>
        conn.rollback()
        throw new com.backoffice.eod.model.TransientFailure(
          s"Ledger mirror failed, transaction rolled back: ${e.getMessage}", e)
    } finally conn.close()
  }

  /** Reconciliation: Delta totals must equal Postgres totals. */
  def reconcile(businessDate: LocalDate): Unit = {
    import spark.implicits._
    val date = businessDate.toString
    val deltaTotals = spark.table("gold.ledger_entries")
      .filter($"business_date" === date)
      .agg(org.apache.spark.sql.functions.sum($"debit_cents").as("dr"),
        org.apache.spark.sql.functions.sum($"credit_cents").as("cr"))
      .collect().head
    val conn = DriverManager.getConnection(jdbcUrl, user, password)
    try {
      val rs = conn.createStatement().executeQuery(
        s"""SELECT SUM(debit_cents), SUM(credit_cents)
           |FROM ledger_entries
           |WHERE business_date = DATE '$date'""".stripMargin)
      rs.next()
      val pgDr = rs.getLong(1)
      val pgCr = rs.getLong(2)
      if (pgDr != deltaTotals.getAs[Long]("dr") ||
        pgCr != deltaTotals.getAs[Long]("cr"))
        throw new com.backoffice.eod.model.PermanentFailure(
          s"Ledger mirror reconciliation failed for $date: " +
            s"Delta (${deltaTotals.getAs[Long]("dr")}, " +
            s"${deltaTotals.getAs[Long]("cr")}) != " +
            s"Postgres ($pgDr, $pgCr)")
    } finally conn.close()
  }
}
