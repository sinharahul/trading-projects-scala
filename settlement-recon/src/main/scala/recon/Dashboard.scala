package recon

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The daily dashboard: one page, five answers. */
case class Dashboard(
  asOfDate: LocalDate,
  totalBreaks: Int,
  openBreaks: Int,
  autoResolved: Int,
  autoResolutionRate: Double,
  breaksByType: Map[BreakType, Int],
  breaksBySeverity: Map[Severity, Int],
  oldestOpenBreak: Option[Break],
  oldestAgeDays: Option[Long],
  totalCashDifference: BigDecimal,
  breaksByCustodian: Map[String, Int],
  breaksByAccount: Map[String, Int]
)

object Dashboard {

  def build(
    allBreaks: Seq[Break],
    resolutions: Seq[Resolution],
    cashReport: CashReconReport,
    asOfDate: LocalDate
  ): Dashboard = {
    val resolvedIds = resolutions.map(_.breakId).toSet
    val open = allBreaks.filterNot(b => resolvedIds.contains(b.breakId))
    val oldestBreak = open.maxByOption(b =>
      ChronoUnit.DAYS.between(b.detectedDate, asOfDate))
    val oldestAge = oldestBreak.map(b =>
      ChronoUnit.DAYS.between(b.detectedDate, asOfDate))
    Dashboard(
      asOfDate = asOfDate,
      totalBreaks = allBreaks.size,
      openBreaks = open.size,
      autoResolved = resolutions.size,
      autoResolutionRate =
        if (allBreaks.isEmpty) 1.0 else resolutions.size.toDouble / allBreaks.size,
      breaksByType = allBreaks.groupBy(_.breakType).map { case (k, v) => k -> v.size },
      breaksBySeverity = allBreaks.groupBy(_.severity).map { case (k, v) => k -> v.size },
      oldestOpenBreak = oldestBreak,
      oldestAgeDays = oldestAge,
      totalCashDifference = cashReport.totalDifference,
      breaksByCustodian = allBreaks.groupBy(_.custodian).map { case (k, v) => k -> v.size },
      breaksByAccount = allBreaks.groupBy(_.account).map { case (k, v) => k -> v.size }
    )
  }

  def render(d: Dashboard): String = {
    val lines = new StringBuilder
    lines.append(s"=== Daily Recon Dashboard — ${d.asOfDate} ===\n")
    lines.append(s"Breaks: ${d.totalBreaks} total, ${d.openBreaks} open, " +
      s"${d.autoResolved} auto-resolved " +
      f"(${d.autoResolutionRate * 100}%.0f%% auto-resolution rate)\n")
    lines.append("By type: " +
      d.breaksByType.map { case (t, c) => s"$t=$c" }.mkString(", ") + "\n")
    lines.append("By severity: " +
      d.breaksBySeverity.map { case (s, c) => s"$s=$c" }.mkString(", ") + "\n")
    d.oldestOpenBreak match {
      case Some(b) =>
        lines.append(s"Oldest open break: ${b.breakId} (${b.breakType}), " +
          s"${d.oldestAgeDays.get} days old — ${b.description}\n")
      case None =>
        lines.append("Oldest open break: none — all breaks resolved\n")
    }
    lines.append(f"Total cash difference: ${d.totalCashDifference}%.2f\n")
    lines.append("By custodian: " +
      d.breaksByCustodian.map { case (c, n) => s"$c=$n" }.mkString(", ") + "\n")
    lines.append("By account: " +
      d.breaksByAccount.map { case (a, n) => s"$a=$n" }.mkString(", ") + "\n")
    lines.toString
  }
}
