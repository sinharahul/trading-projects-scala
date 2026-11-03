package risk.varx

import risk.domain._

/** Expected Shortfall result, paired with its VaR for reporting. */
final case class ESResult(
  confidence: Double,
  esReturn: Double,        // negative: average tail return
  esDollars: BigDecimal,   // positive: average tail loss
  tailCount: Int,          // how many observations fell in the tail
  observationCount: Int
)

object ExpectedShortfall {

  /** Average of returns at or below the VaR threshold return.
    * If no return falls below the threshold (possible with interpolation
    * on tiny samples), falls back to the single worst return, and the
    * tail count of 1 makes the fragility visible.
    */
  def compute(
    positions: List[Position],
    history: Map[String, List[Price]],
    currentValue: BigDecimal,
    confidence: Double
  ): ESResult = {
    val series = HistoricalVaR.portfolioValueSeries(positions, history)
    val returns = HistoricalVaR.simpleReturns(series)
    require(returns.nonEmpty, "need at least two price observations")
    val sorted = returns.map(_._2).sorted.toVector
    val threshold = HistoricalVaR.percentile(sorted, 1.0 - confidence)
    val tail = returns.map(_._2).filter(_ <= threshold)
    val effectiveTail = if (tail.isEmpty) List(sorted.head) else tail
    val esReturn = effectiveTail.sum / effectiveTail.size
    ESResult(
      confidence = confidence,
      esReturn = esReturn,
      esDollars = BigDecimal(-esReturn) * currentValue,
      tailCount = effectiveTail.size,
      observationCount = returns.size
    )
  }
}
