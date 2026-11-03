package risk.domain

import java.time.LocalDate

/** A single position: quantity > 0 means long, quantity < 0 means short. */
final case class Position(
  accountId: String,
  deskId: String,
  symbol: String,
  quantity: BigDecimal,       // signed: +long, -short
  costBasis: BigDecimal       // average cost per share, for P&L attribution
)

/** A market price observation. `asOf` matters: stale prices must be detectable. */
final case class Price(
  symbol: String,
  asOf: LocalDate,
  close: BigDecimal,
  volume: Long = 0L
)

/** A price tick arriving intraday. */
final case class PriceTick(
  symbol: String,
  price: BigDecimal,
  timestampMillis: Long
)

/** Static reference data for a symbol: beta and sector for adjusted exposure. */
final case class SymbolInfo(
  symbol: String,
  sector: String,
  beta: Double,               // beta vs the broad market, e.g. 1.2
  borrowRate: Double = 0.0    // annualised stock-loan rate for shorts
)

/** An account with its margin parameters. */
final case class Account(
  accountId: String,
  deskId: String,
  accountType: AccountType,   // Cash, RegT Margin, or Portfolio Margin
  cashBalance: BigDecimal
)

sealed trait AccountType
object AccountType {
  case object Cash extends AccountType
  case object RegTMargin extends AccountType
  case object PortfolioMargin extends AccountType
}
