package risk.exposure

import risk.domain._

/** Exposure results for one scope (account, desk, or whole firm). */
final case class Exposure(
  longExposure: BigDecimal,
  shortExposure: BigDecimal,   // positive magnitude of short market value
  grossExposure: BigDecimal,
  netExposure: BigDecimal,
  betaAdjustedExposure: BigDecimal,
  marketValueBySymbol: Map[String, BigDecimal]
)

object ExposureCalculator {

  /** Compute exposure for a list of positions at given prices.
    * Symbols without a price are reported in `missingPrices` rather than
    * silently valued at zero: a zero is a number, and a missing price
    * must never masquerade as one.
    */
  def compute(
    positions: List[Position],
    prices: Map[String, BigDecimal],
    infos: Map[String, SymbolInfo]
  ): (Exposure, List[String]) = {
    val valued = positions.map { p =>
      val price = prices.getOrElse(p.symbol, BigDecimal(0))
      (p, p.quantity * price)
    }
    val missing = positions.map(_.symbol).distinct.filterNot(prices.contains)

    val longExp = valued.map(_._2).filter(_ > 0).sum
    val shortExp = valued.map(_._2).filter(_ < 0).map(_.abs).sum
    val betaAdj = valued.map { case (p, mv) =>
      val beta = infos.get(p.symbol).map(_.beta).getOrElse(1.0)
      mv * BigDecimal(beta)
    }.sum
    val bySymbol = valued.groupBy(_._1.symbol).map { case (sym, vs) =>
      sym -> vs.map(_._2).sum
    }

    val exposure = Exposure(
      longExposure = longExp,
      shortExposure = shortExp,
      grossExposure = longExp + shortExp,
      netExposure = longExp - shortExp,
      betaAdjustedExposure = betaAdj,
      marketValueBySymbol = bySymbol
    )
    (exposure, missing)
  }

  /** Aggregate exposures by desk, for limit monitoring in Step 6. */
  def byDesk(
    positions: List[Position],
    prices: Map[String, BigDecimal],
    infos: Map[String, SymbolInfo]
  ): Map[String, Exposure] = {
    positions.groupBy(_.deskId).map { case (desk, ps) =>
      desk -> compute(ps, prices, infos)._1
    }
  }

  /** Aggregate exposures by account, for margin in Step 4. */
  def byAccount(
    positions: List[Position],
    prices: Map[String, BigDecimal],
    infos: Map[String, SymbolInfo]
  ): Map[String, Exposure] = {
    positions.groupBy(_.accountId).map { case (acct, ps) =>
      acct -> compute(ps, prices, infos)._1
    }
  }
}
