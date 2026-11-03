package positions

import java.sql.Timestamp

case class PositionRow(
  account: String,
  symbol: String,
  quantity: Long,
  totalCost: BigDecimal,
  realizedPnl: BigDecimal,
  tradeCount: Long,
  lastTradeTime: Timestamp,
  version: Long
) {
  def toState: PositionState = PositionState(account, symbol, quantity,
    totalCost, Nil, realizedPnl, tradeCount, lastTradeTime, version)
}
