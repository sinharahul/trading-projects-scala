package risk.stress

import risk.domain._
import risk.exposure.Exposure
import risk.margin.{MarginAccount, MarginCalculator}

/** A stress scenario: a name, a written rationale, and a per-symbol
  * percentage price shock (e.g. -0.37 means the price falls 37%).
  * Symbols not listed are assumed unchanged — and that assumption is
  * visible in the code, not hidden in a default.
  */
final case class Scenario(
  name: String,
  rationale: String,
  shocks: Map[String, Double]
)

/** The result of applying one scenario to one book. */
final case class StressResult(
  scenarioName: String,
  stressedPrices: Map[String, BigDecimal],
  stressedNetValue: BigDecimal,
  pnl: BigDecimal,
  stressedExposure: Exposure,
  stressedMarginCall: BigDecimal
)

object StressEngine {

  /** Standard scenario library. Add scenarios here, never ad hoc in
    * calling code: the suite must be reviewable as a whole.
    */
  val scenarios: List[Scenario] = List(
    Scenario(
      "2008-style crisis",
      "Broad drawdown; defensives fall less; no diversification benefit assumed beyond the stated shocks.",
      Map("ACME" -> -0.37, "GLOB" -> -0.30, "STAB" -> -0.15)
    ),
    Scenario(
      "Flash crash (trough)",
      "Sharp intraday dislocation at trough values; no recovery assumed, since margin is called on troughs.",
      Map("ACME" -> -0.09, "GLOB" -> -0.07, "STAB" -> -0.04)
    ),
    Scenario(
      "Correlation break",
      "All names fall together regardless of beta or sector; attacks the diversification assumption.",
      Map("ACME" -> -0.20, "GLOB" -> -0.20, "STAB" -> -0.20)
    ),
    Scenario(
      "Single-name shock: ACME -50%",
      "Isolates concentration risk in the largest position.",
      Map("ACME" -> -0.50)
    )
  )

  /** Apply one scenario to a book. Returns stressed prices, P&L, the
    * stressed exposure, and the margin call that would be due on the
    * stressed book for the given margin account.
    */
  def applyScenario(
    scenario: Scenario,
    positions: List[Position],
    prices: Map[String, BigDecimal],
    infos: Map[String, SymbolInfo],
    account: MarginAccount
  ): StressResult = {
    val stressedPrices = prices.map { case (sym, p) =>
      val shock = scenario.shocks.getOrElse(sym, 0.0)
      sym -> (p * BigDecimal(1.0 + shock))
    }
    val currentNet = positions.map(p => p.quantity * prices(p.symbol)).sum
    val stressedNet = positions.map(p => p.quantity * stressedPrices(p.symbol)).sum
    val (stressedExp, _) =
      risk.exposure.ExposureCalculator.compute(positions, stressedPrices, infos)
    val stressedCall = MarginCalculator.compute(
      account, stressedExp, infos).marginCall
    StressResult(
      scenarioName = scenario.name,
      stressedPrices = stressedPrices,
      stressedNetValue = stressedNet,
      pnl = stressedNet - currentNet,
      stressedExposure = stressedExp,
      stressedMarginCall = stressedCall
    )
  }

  /** Run the whole suite and return results in suite order. */
  def runSuite(
    positions: List[Position],
    prices: Map[String, BigDecimal],
    infos: Map[String, SymbolInfo],
    account: MarginAccount
  ): List[StressResult] =
    scenarios.map(s => applyScenario(s, positions, prices, infos, account))
}
