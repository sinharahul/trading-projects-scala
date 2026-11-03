package risk.varx

import java.time.LocalDate
import risk.domain._

/** One day of portfolio history: the date and the repriced value. */
final case class PortfolioValue(date: LocalDate, value: BigDecimal)

final case class VaRResult(
  confidence: Double,        // 0.95 or 0.99
  horizonDays: Int,
  varReturn: Double,         // negative: the threshold return
  varDollars: BigDecimal,    // positive: the loss amount
  worstReturn: Double,
  worstDate: LocalDate,
  observationCount: Int
)

object HistoricalVaR {

  /** Reprice today's positions at every historical date's prices.
    * Dates must be present for every symbol; missing data fails loudly.
    */
  def portfolioValueSeries(
    positions: List[Position],
    history: Map[String, List[Price]]
  ): List[PortfolioValue] = {
    val dates = history.values.head.map(_.asOf).sorted
    val bySymbolDate: Map[(String, LocalDate), BigDecimal] =
      history.flatMap { case (sym, ps) =>
        ps.map(p => (sym, p.asOf) -> p.close)
      }
    dates.map { d =>
      val value = positions.map { p =>
        val price = bySymbolDate.getOrElse(
          (p.symbol, d),
          throw new IllegalArgumentException(
            s"missing price for ${p.symbol} on $d"))
        p.quantity * price
      }.sum
      PortfolioValue(d, value)
    }
  }

  /** Simple daily returns from a value series: (V(t)/V(t-1)) - 1. */
  def simpleReturns(series: List[PortfolioValue]): List[(LocalDate, Double)] = {
    series.sliding(2).collect {
      case List(prev, curr) =>
        curr.date -> (curr.value.toDouble / prev.value.toDouble - 1.0)
    }.toList
  }

  /** Log returns: ln(V(t)/V(t-1)). Provided for comparison; the project
    * standard is simple returns, and the two must never be mixed in one
    * calculation.
    */
  def logReturns(series: List[PortfolioValue]): List[(LocalDate, Double)] = {
    series.sliding(2).collect {
      case List(prev, curr) =>
        curr.date -> math.log(curr.value.toDouble / prev.value.toDouble)
    }.toList
  }

  /** p-th percentile of a sorted sample by linear interpolation.
    * `p` is a fraction: 0.05 for the 5th percentile.
    * This is the same convention used by most statistics libraries.
    */
  def percentile(sorted: Vector[Double], p: Double): Double = {
    require(sorted.nonEmpty, "cannot take a percentile of an empty sample")
    require(p >= 0.0 && p <= 1.0, "percentile must be between 0 and 1")
    if (sorted.size == 1) return sorted.head
    val rank = p * (sorted.size - 1)
    val lo = math.floor(rank).toInt
    val hi = math.ceil(rank).toInt
    if (lo == hi) sorted(lo)
    else sorted(lo) + (rank - lo) * (sorted(hi) - sorted(lo))
  }

  /** Historical VaR at the given confidence, 1-day horizon.
    * `currentValue` is today's portfolio value; VaR dollars are
    * reported as a positive loss amount.
    */
  def compute(
    positions: List[Position],
    history: Map[String, List[Price]],
    currentValue: BigDecimal,
    confidence: Double
  ): VaRResult = {
    val series = portfolioValueSeries(positions, history)
    val returns = simpleReturns(series)
    require(returns.nonEmpty, "need at least two price observations")
    val sorted = returns.map(_._2).sorted.toVector
    val threshold = percentile(sorted, 1.0 - confidence)
    val worst = returns.minBy(_._2)
    VaRResult(
      confidence = confidence,
      horizonDays = 1,
      varReturn = threshold,
      varDollars = BigDecimal(-threshold) * currentValue,
      worstReturn = worst._2,
      worstDate = worst._1,
      observationCount = returns.size
    )
  }
}
