package risk.margin

import risk.domain._
import risk.exposure.Exposure

/** Margin account view: cash on hand and amount borrowed from the broker. */
final case class MarginAccount(
  accountId: String,
  cashBalance: BigDecimal,
  debitBalance: BigDecimal
)

final case class MarginResult(
  accountId: String,
  equity: BigDecimal,
  initialRequirement: BigDecimal,
  maintenanceRequirement: BigDecimal,
  excessOverMaintenance: BigDecimal, // negative means a margin call
  marginCall: BigDecimal,            // zero when no call is due
  portfolioMarginRequirement: BigDecimal
)

object MarginCalculator {

  /** Equity = cash + net market value − debit balance. */
  def equity(account: MarginAccount, exp: Exposure): BigDecimal =
    account.cashBalance + exp.netExposure - account.debitBalance

  /** Regulation T style initial margin: 50% of long value plus
    * 150% of short value (sale proceeds plus 50% additional).
    */
  def regTInitial(exp: Exposure): BigDecimal =
    exp.longExposure * BigDecimal("0.50") +
      exp.shortExposure * BigDecimal("1.50")

  /** Maintenance margin: 25% of long value plus 30% of short value. */
  def maintenance(exp: Exposure): BigDecimal =
    exp.longExposure * BigDecimal("0.25") +
      exp.shortExposure * BigDecimal("0.30")

  /** Simple portfolio-margin model: each position contributes
    * |market value| × haircut, where the haircut scales with beta,
    * shorts carry an extra 5 points for borrow and buy-in risk, and
    * any symbol over 50% of gross exposure adds a 10% concentration
    * charge on its own market value. This is a teaching model, not a
    * regulatory model: real portfolio margin uses scenario grids.
    */
  def portfolioMargin(
    exp: Exposure,
    infos: Map[String, SymbolInfo]
  ): BigDecimal = {
    val base = exp.marketValueBySymbol.toList.map { case (sym, mv) =>
      val beta = infos.get(sym).map(_.beta).getOrElse(1.0)
      val haircut = math.min(0.50, 0.15 * beta)
      val shortUplift = if (mv < 0) 0.05 else 0.0
      mv.abs * BigDecimal(haircut + shortUplift)
    }.sum
    val concentration = exp.marketValueBySymbol.toList.map { case (sym, mv) =>
      val share = if (exp.grossExposure == 0) 0.0
        else mv.abs.toDouble / exp.grossExposure.toDouble
      if (share > 0.50) mv.abs * BigDecimal("0.10") else BigDecimal(0)
    }.sum
    base + concentration
  }

  def compute(
    account: MarginAccount,
    exp: Exposure,
    infos: Map[String, SymbolInfo]
  ): MarginResult = {
    val eq = equity(account, exp)
    val maint = maintenance(exp)
    val excess = eq - maint
    MarginResult(
      accountId = account.accountId,
      equity = eq,
      initialRequirement = regTInitial(exp),
      maintenanceRequirement = maint,
      excessOverMaintenance = excess,
      marginCall = if (excess < 0) -excess else BigDecimal(0),
      portfolioMarginRequirement = portfolioMargin(exp, infos)
    )
  }
}
