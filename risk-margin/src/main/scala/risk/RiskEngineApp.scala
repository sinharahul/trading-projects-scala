package risk

import risk.domain.Fixtures
import risk.exposure.ExposureCalculator
import risk.limits.{InMemoryAlertQueue, LimitChecker}
import risk.margin.{MarginAccount, MarginCalculator}
import risk.report.{ReportGenerator, RiskReport}
import risk.returns.PriceHistoryLoader
import risk.stress.StressEngine
import risk.varx.{ExpectedShortfall, HistoricalVaR}

/** End-to-end risk batch: positions and prices in, report out. */
object RiskEngineApp extends App {
  val history = PriceHistoryLoader.load("src/main/resources/data/prices.csv")
  val positions = Fixtures.bookA1
  val prices = Fixtures.prices
  val infos = Fixtures.infos
  val account = MarginAccount("A2", BigDecimal(10000), BigDecimal(120000))
  val currentValue = BigDecimal(143000)

  val (exposure, missing) =
    ExposureCalculator.compute(positions, prices, infos)
  require(missing.isEmpty, s"missing prices: $missing")

  val var95 = HistoricalVaR.compute(positions, history, currentValue, 0.95)
  val var99 = HistoricalVaR.compute(positions, history, currentValue, 0.99)
  val es95 = ExpectedShortfall.compute(positions, history, currentValue, 0.95)
  val margin = MarginCalculator.compute(account, exposure, infos)
  val stress = StressEngine.runSuite(positions, prices, infos, account)
  val breaches = LimitChecker.evaluate("DESK-1", exposure, var99)

  val queue = new InMemoryAlertQueue
  LimitChecker.publishNewBreaches(breaches, queue, Set.empty,
    System.currentTimeMillis())
  val alerts = queue.drain()

  val report = RiskReport(
    asOf = "2026-09-29",
    accountId = "A2",
    exposure = exposure,
    var95 = var95,
    var99 = var99,
    es95 = es95,
    margin = margin,
    stressResults = stress,
    breaches = breaches,
    stalePriceSymbols = Nil,
    assumptions = List(
      "Historical 1-day VaR/ES from 20 daily returns (2026-09-01 to 2026-09-29).",
      "99% figures interpolate between the two worst days; treat as noisy.",
      "ES 95% averages a single tail observation; treat as a floor.",
      "Reg T style margin: 50%/150% initial, 25%/30% maintenance.",
      "Portfolio margin: beta-scaled haircuts, 5-point short uplift, " +
        "10% concentration charge above 50% share.",
      "Stress shocks are instantaneous, with no recovery or liquidity haircut."
    )
  )

  println(ReportGenerator.toMarkdown(report))
  println("--- JSON summary ---")
  println(ReportGenerator.toJsonSummary(report))
  println(s"--- Alerts queued: ${alerts.size} ---")
  alerts.foreach(a => println(s"[${a.key}] ${a.breach.message}"))
}
