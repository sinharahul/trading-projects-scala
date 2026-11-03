package positions

import java.sql.Timestamp

/** Pure position-transition logic. No Spark types here, so this object
  * is unit-testable and reusable in streaming, batch, and test code.
  */
object PositionEngine {

  /** Apply one trade to a position under the average-cost method.
    * Returns the new position state.
    */
  def applyTradeAverageCost(state: PositionState, trade: Trade): PositionState = {
    val signedQty = Side.sign(trade.side) * trade.quantity
    val tradeCost = Money.cost(trade.quantity, trade.price)

    // Signed cost: buys add cost, sells remove cost at the trade price.
    val signedTradeCost = trade.side match {
      case Buy  => tradeCost
      case Sell => -tradeCost
    }

    val newQuantity = state.quantity + signedQty

    if (state.isFlat) {
      // Case 4: opening a position from flat.
      state.copy(
        quantity = signedQty,
        totalCost = signedTradeCost,
        lots = Nil, // average-cost path does not maintain lots
        tradeCount = state.tradeCount + 1,
        lastTradeTime = trade.tradeTime,
        version = state.version + 1
      )
    } else if ((state.quantity > 0 && signedQty > 0) ||
               (state.quantity < 0 && signedQty < 0)) {
      // Case 1: increasing the position in the same direction.
      state.copy(
        quantity = newQuantity,
        totalCost = Money.money(state.totalCost + signedTradeCost),
        tradeCount = state.tradeCount + 1,
        lastTradeTime = trade.tradeTime,
        version = state.version + 1
      )
    } else {
      // Cases 2 and 3: reducing, or flipping through, the position.
      val closingQty = math.min(math.abs(state.quantity), trade.quantity)
      // Cost basis of the closed portion, at the old average cost.
      val closingCostBasis = Money.money(
        BigDecimal(closingQty) * state.averageCost)
      // Proceeds (for a sell) or buy-back cost (for a buy to cover)
      // attributable to the closed portion.
      val closingProceeds = Money.cost(closingQty, trade.price)
      // Realized P&L sign: for a long position being sold, proceeds
      // minus cost basis; for a short position being covered, cost
      // basis (the short proceeds) minus buy-back cost. Both reduce
      // to the same formula when quantities and costs are signed:
      // realized = signed proceeds of the closed portion minus the
      // signed cost basis released.
      val realizedDelta =
        if (state.isLong) closingProceeds - closingCostBasis
        else closingCostBasis - closingProceeds

      if (trade.quantity <= math.abs(state.quantity)) {
        // Case 2: partial or full close, no flip.
        val remainingQty = state.quantity + signedQty
        // Remaining cost basis scales proportionally with quantity.
        val remainingCost =
          if (remainingQty == 0) BigDecimal(0)
          else Money.money(state.totalCost -
            (if (state.isLong) closingCostBasis else -closingCostBasis))
        state.copy(
          quantity = remainingQty,
          totalCost = remainingCost,
          realizedPnl = Money.money(state.realizedPnl + realizedDelta),
          tradeCount = state.tradeCount + 1,
          lastTradeTime = trade.tradeTime,
          version = state.version + 1
        )
      } else {
        // Case 3: flip. The excess opens a new position at the trade price.
        val excessQty = trade.quantity - math.abs(state.quantity)
        val newSignedQty = Side.sign(trade.side) * excessQty
        val newCost = trade.side match {
          case Buy  => Money.cost(excessQty, trade.price)
          case Sell => -Money.cost(excessQty, trade.price)
        }
        state.copy(
          quantity = newSignedQty,
          totalCost = newCost,
          realizedPnl = Money.money(state.realizedPnl + realizedDelta),
          tradeCount = state.tradeCount + 1,
          lastTradeTime = trade.tradeTime,
          version = state.version + 1
        )
      }
    }
  }
}
