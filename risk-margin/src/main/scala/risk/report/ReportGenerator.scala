package risk.report

import risk.domain._
import risk.exposure.Exposure
import risk.limits.Breach
import risk.margin.MarginResult
import risk.stress.StressResult
import risk.varx.{ESResult, VaRResult}

/** Everything one daily risk report needs, assembled by the caller
  * from the engines built in Steps 1–7.
  */
final case class RiskReport(
  asOf: String,
  accountId: String,
  exposure: Exposure,
  var95: VaRResult,
  var99: VaRResult,
  es95: ESResult,
  margin: MarginResult,
  stressResults: List[StressResult],
  breaches: List[Breach],
  stalePriceSymbols: List[String],
  assumptions: List[String]
)

object ReportGenerator {

  def toMarkdown(r: RiskReport): String = {
    val stressLines = r.stressResults.sortBy(_.pnl).map { s =>
      s"| ${s.scenarioName} | ${s.pnl} | ${s.stressedNetValue} |"
    }.mkString("\n")
    val breachLines = if (r.breaches.isEmpty) "None active."
      else r.breaches.map(b => s"- ${b.message}").mkString("\n")
    val staleLines = if (r.stalePriceSymbols.isEmpty) "None."
      else r.stalePriceSymbols.mkString(", ")
    val assumptionLines = r.assumptions.map(a => s"- $a").mkString("\n")

    s"""# Daily Risk Report — Account ${r.accountId} (as of ${r.asOf})
       |
       |## Exposure
       |
       || Measure | Dollars |
       ||---------|---------|
       || Long exposure | ${r.exposure.longExposure} |
       || Short exposure | ${r.exposure.shortExposure} |
       || Gross exposure | ${r.exposure.grossExposure} |
       || Net exposure | ${r.exposure.netExposure} |
       || Beta-adjusted exposure | ${r.exposure.betaAdjustedExposure} |
       |
       |## Value at Risk and Expected Shortfall (1-day)
       |
       || Measure | Loss (dollars) | Basis |
       ||---------|----------------|-------|
       || VaR 95% | ${r.var95.varDollars} | ${r.var95.observationCount} observations |
       || VaR 99% | ${r.var99.varDollars} | ${r.var99.observationCount} observations |
       || Expected Shortfall 95% | ${r.es95.esDollars} | ${r.es95.tailCount} tail observations |
       |
       |## Margin
       |
       || Measure | Dollars |
       ||---------|---------|
       || Equity | ${r.margin.equity} |
       || Reg T initial requirement | ${r.margin.initialRequirement} |
       || Maintenance requirement | ${r.margin.maintenanceRequirement} |
       || Portfolio-margin requirement | ${r.margin.portfolioMarginRequirement} |
       || Margin call due | ${r.margin.marginCall} |
       |
       |## Stress tests (worst first)
       |
       || Scenario | P&L | Stressed net value |
       ||----------|-----|--------------------|
       |$stressLines
       |
       |## Active limit breaches
       |
       |$breachLines
       |
       |## Stale prices
       |
       |$staleLines
       |
       |## Assumptions and limitations
       |
       |$assumptionLines
       |""".stripMargin
  }

  /** Compact JSON summary for downstream systems. Built by hand to
    * keep this project dependency-free; production systems should use
    * a JSON library and a schema version.
    */
  def toJsonSummary(r: RiskReport): String =
    s"""{
       |  "asOf": "${r.asOf}",
       |  "accountId": "${r.accountId}",
       |  "grossExposure": ${r.exposure.grossExposure},
       |  "netExposure": ${r.exposure.netExposure},
       |  "var95": ${r.var95.varDollars},
       |  "var99": ${r.var99.varDollars},
       |  "es95": ${r.es95.esDollars},
       |  "marginCall": ${r.margin.marginCall},
       |  "worstStressPnl": ${r.stressResults.map(_.pnl).min},
       |  "activeBreaches": ${r.breaches.size}
       |}""".stripMargin
}
