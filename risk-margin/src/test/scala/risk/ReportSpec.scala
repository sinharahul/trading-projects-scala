package risk

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import risk.domain.Fixtures
import risk.exposure.ExposureCalculator
import risk.limits.LimitChecker
import risk.margin.{MarginAccount, MarginCalculator}
import risk.report.{ReportGenerator, RiskReport}
import risk.returns.PriceHistoryLoader
import risk.stress.StressEngine
import risk.varx.{ExpectedShortfall, HistoricalVaR}

class ReportSpec extends AnyFlatSpec with Matchers {
  "ReportGenerator" should "contain every headline figure" in {
    val history = PriceHistoryLoader.load("src/main/resources/data/prices.csv")
    val (exp, _) = ExposureCalculator.compute(
      Fixtures.bookA1, Fixtures.prices, Fixtures.infos)
    val account = MarginAccount("A2", BigDecimal(10000), BigDecimal(120000))
    val report = RiskReport(
      asOf = "2026-09-29",
      accountId = "A2",
      exposure = exp,
      var95 = HistoricalVaR.compute(Fixtures.bookA1, history, BigDecimal(143000), 0.95),
      var99 = HistoricalVaR.compute(Fixtures.bookA1, history, BigDecimal(143000), 0.99),
      es95 = ExpectedShortfall.compute(Fixtures.bookA1, history, BigDecimal(143000), 0.95),
      margin = MarginCalculator.compute(account, exp, Fixtures.infos),
      stressResults = StressEngine.runSuite(
        Fixtures.bookA1, Fixtures.prices, Fixtures.infos, account),
      breaches = LimitChecker.evaluate("DESK-1", exp,
        HistoricalVaR.compute(Fixtures.bookA1, history, BigDecimal(143000), 0.99)),
      stalePriceSymbols = Nil,
      assumptions = List("Historical 1-day VaR/ES from 20 observations.")
    )
    val md = ReportGenerator.toMarkdown(report)
    md should include("187000")
    md should include("14850")
    md should include("-60000")
    md should include("Assumptions and limitations")
    val json = ReportGenerator.toJsonSummary(report)
    json should include("\"marginCall\": 14850")
    json should include("\"activeBreaches\": 3")
  }
}
