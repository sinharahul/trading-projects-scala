package risk.exposure

import risk.domain._

object Concentration {
  /** Largest single-symbol share of gross exposure, as a fraction 0..1. */
  def maxSymbolShare(exp: Exposure): Double = {
    if (exp.grossExposure == 0) 0.0
    else exp.marketValueBySymbol.values.map(_.abs).max.toDouble /
      exp.grossExposure.toDouble
  }

  /** Largest sector share of gross exposure. */
  def maxSectorShare(
    exp: Exposure,
    infos: Map[String, SymbolInfo]
  ): Double = {
    if (exp.grossExposure == 0) 0.0
    else {
      val bySector = exp.marketValueBySymbol.toList.map { case (sym, mv) =>
        infos.get(sym).map(_.sector).getOrElse("Unknown") -> mv.abs
      }.groupBy(_._1).map { case (s, vs) => s -> vs.map(_._2).sum }
      bySector.values.max.toDouble / exp.grossExposure.toDouble
    }
  }
}
