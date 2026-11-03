package recon

import java.time.LocalDate
import java.time.temporal.ChronoUnit


/** One row of the aging report. */
case class AgingBucket(
  label: String,
  minDays: Int,
  maxDays: Option[Int],
  count: Int,
  breakIds: Seq[String]
)

case class AgingReport(
  asOfDate: LocalDate,
  buckets: Seq[AgingBucket],
  totalOpen: Int
)

object AgingReport {
  private val bucketDefs: Seq[(String, Int, Option[Int])] = Seq(
    ("0-1 days", 0, Some(1)),
    ("2-5 days", 2, Some(5)),
    ("6-10 days", 6, Some(10)),
    ("11-30 days", 11, Some(30)),
    ("Over 30 days", 31, None)
  )

  /** Builds an aging report for the given open breaks. `openBreaks` should
    * be the breaks that are NOT auto-resolved — resolved breaks are closed
    * and do not age. */
  def build(openBreaks: Seq[Break], asOfDate: LocalDate): AgingReport = {
    val buckets = bucketDefs.map { case (label, min, max) =>
      val inBucket = openBreaks.filter { b =>
        val age = ChronoUnit.DAYS.between(b.detectedDate, asOfDate).toInt
        age >= min && max.forall(m => age <= m)
      }
      AgingBucket(label, min, max, inBucket.size, inBucket.map(_.breakId))
    }
    AgingReport(asOfDate, buckets, openBreaks.size)
  }

  def render(report: AgingReport): String = {
    val lines = new StringBuilder
    lines.append(s"Aging report as of ${report.asOfDate} (total open: ${report.totalOpen})\n")
    report.buckets.foreach { b =>
      lines.append(f"  ${b.label}%-14s ${b.count}%3d  ${b.breakIds.mkString(", ")}\n")
    }
    lines.toString
  }
}


/** One row of the cash reconciliation. */
case class CashReconRow(
  custodian: String,
  account: String,
  currency: String,
  valueDate: LocalDate,
  expectedCash: BigDecimal,
  actualCash: BigDecimal
) {
  def difference: BigDecimal = actualCash - expectedCash
  def isBalanced: Boolean = difference == 0
}

case class CashReconReport(rows: Seq[CashReconRow]) {
  def unbalanced: Seq[CashReconRow] = rows.filterNot(_.isBalanced)
  def totalDifference: BigDecimal =
    rows.map(_.difference).foldLeft(BigDecimal(0))(_ + _)
}

object CashReconciliation {

  /** Groups instructions and statement entries by custodian, account,
    * currency, and date, then compares expected versus actual cash.
    * Instructions contribute their statement-sign cash on their settlement
    * date; statement entries contribute their cash on their value date.
    * All statement entry types count here, including FEE and TAX lines. */
  def reconcile(
    instructions: Seq[SettlementInstruction],
    statements: Seq[StatementEntry]
  ): CashReconReport = {
    type Key = (String, String, String, LocalDate)

    val expected: Map[Key, BigDecimal] = instructions
      .groupBy(i => (i.custodian, i.account, i.currency, i.settlementDate))
      .map { case (key, insts) =>
        key -> insts.map(StatementConventions.statementCash).foldLeft(BigDecimal(0))(_ + _)
      }

    val actual: Map[Key, BigDecimal] = statements
      .groupBy(s => (s.custodian, s.account, s.currency, s.valueDate))
      .map { case (key, entries) =>
        key -> entries.map(_.cashAmount).foldLeft(BigDecimal(0))(_ + _)
      }

    val allKeys = (expected.keySet ++ actual.keySet).toSeq.sortBy(k => (k._4, k._2))
    val rows = allKeys.map { case (custodian, account, currency, date) =>
      CashReconRow(
        custodian = custodian,
        account = account,
        currency = currency,
        valueDate = date,
        expectedCash = expected.getOrElse((custodian, account, currency, date), BigDecimal(0)),
        actualCash = actual.getOrElse((custodian, account, currency, date), BigDecimal(0))
      )
    }
    CashReconReport(rows)
  }

  def render(report: CashReconReport): String = {
    val lines = new StringBuilder
    lines.append("Cash reconciliation (expected vs actual, statement sign)\n")
    report.rows.foreach { r =>
      val flag = if (r.isBalanced) "OK " else "DIFF"
      lines.append(f"  $flag ${r.custodian}%-12s ${r.account} ${r.currency} " +
        f"${r.valueDate} expected=${r.expectedCash}%15.2f " +
        f"actual=${r.actualCash}%15.2f diff=${r.difference}%12.2f\n")
    }
    lines.append(f"Total difference: ${report.totalDifference}%.2f\n")
    lines.toString
  }
}
