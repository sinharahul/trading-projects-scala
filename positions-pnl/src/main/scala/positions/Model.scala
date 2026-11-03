package positions

import java.sql.Timestamp

sealed trait Side
case object Buy extends Side
case object Sell extends Side

object Side {
  def fromString(s: String): Side = s.toUpperCase match {
    case "BUY"  => Buy
    case "SELL" => Sell
    case other  => throw new IllegalArgumentException(s"Unknown side: $other")
  }
  def sign(side: Side): Long = side match {
    case Buy  => 1L
    case Sell => -1L
  }
}

/** A validated trade event, as produced by Project 2. */
case class Trade(
  tradeId: String,
  account: String,
  symbol: String,
  side: Side,
  quantity: Long,          // always positive; side carries the direction
  price: BigDecimal,       // per-share execution price
  tradeTime: Timestamp,
  source: String           // e.g. "FIX", "OMS", "MANUAL"
)

/** One FIFO lot: a quantity bought at one price. */
case class Lot(quantity: Long, price: BigDecimal) {
  require(quantity > 0, "a lot must have positive quantity")
}

/** Full position state for one (account, symbol) key.
  *
  * @param quantity signed quantity; positive = long, negative = short
  * @param totalCost signed total cost of the shares currently held.
  *                  For a long position this is positive; for a short
  *                  position it is negative (proceeds owed back).
  * @param lots FIFO queue; empty for a flat position. For a short
  *             position the lots represent the short sale tranches.
  * @param realizedPnl cumulative realized P&L for this key, in dollars.
  * @param tradeCount number of trades applied to this position.
  * @param version monotonically increasing state version, for debugging
  *                and for optimistic-concurrency checks downstream.
  */
case class PositionState(
  account: String,
  symbol: String,
  quantity: Long,
  totalCost: BigDecimal,
  lots: List[Lot],
  realizedPnl: BigDecimal,
  tradeCount: Long,
  lastTradeTime: Timestamp,
  version: Long
) {
  def isLong: Boolean = quantity > 0
  def isShort: Boolean = quantity < 0
  def isFlat: Boolean = quantity == 0

  /** Average cost per share, or zero for a flat position. */
  def averageCost: BigDecimal =
    if (quantity == 0) BigDecimal(0)
    else Money.averageCost(totalCost, quantity)
}

object PositionState {
  def empty(account: String, symbol: String, now: Timestamp): PositionState =
    PositionState(account, symbol, 0L, BigDecimal(0), Nil,
      BigDecimal(0), 0L, now, 0L)
}

/** A point-in-time position snapshot, enriched with market data. */
case class PositionSnapshot(
  account: String,
  symbol: String,
  snapshotTime: Timestamp,
  quantity: Long,
  averageCost: BigDecimal,
  marketPrice: BigDecimal,
  marketValue: BigDecimal,
  unrealizedPnl: BigDecimal,
  realizedPnl: BigDecimal,
  totalPnl: BigDecimal,
  snapshotType: String // "INTRADAY" or "EOD"
)

/** One reconciliation difference. */
case class PositionBreak(
  account: String,
  symbol: String,
  snapshotDate: java.sql.Date,
  expectedQuantity: Long,  // from the external source (custodian, etc.)
  actualQuantity: Long,    // from our engine
  quantityDifference: Long,
  expectedValue: BigDecimal,
  actualValue: BigDecimal,
  status: String,          // OPEN, EXPLAINED, RESOLVED
  reasonCode: String
)
