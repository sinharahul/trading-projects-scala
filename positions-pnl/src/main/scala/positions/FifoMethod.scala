package positions

/** FIFO cost-basis implementation.
  *
  * Long positions keep lots in buy order; sells consume from the front.
  * Short positions keep "short lots" (negative quantities recorded as
  * positive quantities with a short flag carried by the position sign);
  * buys consume them from the front. The position quantity sign tells
  * you which interpretation applies.
  */
object FifoMethod extends CostBasisMethod {
  val name = "FIFO"

  /** Consume `qty` shares from the front of the lot queue.
    * Returns (remaining lots, cost basis of the consumed shares).
    * The caller guarantees qty <= total lot quantity.
    */
  def consumeLots(lots: List[Lot], qty: Long): (List[Lot], BigDecimal) = {
    require(qty >= 0, "consumption quantity must be non-negative")
    if (qty == 0) return (lots, BigDecimal(0))
    lots match {
      case Nil =>
        throw new IllegalStateException(
          "FIFO consumption exceeded available lots")
      case head :: tail if head.quantity <= qty =>
        // Whole lot consumed; recurse on the remainder.
        val (rest, restCost) = consumeLots(tail, qty - head.quantity)
        (rest, Money.money(BigDecimal(head.quantity) * head.price) + restCost)
      case head :: tail =>
        // Partial consumption: the lot survives with reduced quantity.
        val consumedCost = Money.money(BigDecimal(qty) * head.price)
        (Lot(head.quantity - qty, head.price) :: tail, consumedCost)
    }
  }

  /** Total quantity across all lots. */
  def lotQuantity(lots: List[Lot]): Long =
    lots.map(_.quantity).sum

  def applyTrade(state: PositionState, trade: Trade): PositionState = {
    val now = trade.tradeTime
    if (state.isFlat) {
      // Opening a position: one lot at the trade price.
      state.copy(
        quantity = Side.sign(trade.side) * trade.quantity,
        totalCost = trade.side match {
          case Buy  => Money.cost(trade.quantity, trade.price)
          case Sell => -Money.cost(trade.quantity, trade.price)
        },
        lots = List(Lot(trade.quantity, trade.price)),
        tradeCount = state.tradeCount + 1,
        lastTradeTime = now,
        version = state.version + 1
      )
    } else if ((state.isLong && trade.side == Buy) ||
               (state.isShort && trade.side == Sell)) {
      // Increasing: append a lot, extend total cost.
      val signedCost = trade.side match {
        case Buy  => Money.cost(trade.quantity, trade.price)
        case Sell => -Money.cost(trade.quantity, trade.price)
      }
      state.copy(
        quantity = state.quantity + Side.sign(trade.side) * trade.quantity,
        totalCost = Money.money(state.totalCost + signedCost),
        lots = state.lots :+ Lot(trade.quantity, trade.price),
        tradeCount = state.tradeCount + 1,
        lastTradeTime = now,
        version = state.version + 1
      )
    } else {
      // Reducing or flipping.
      val available = lotQuantity(state.lots)
      val closingQty = math.min(available, trade.quantity)
      val (remainingLots, closingCostBasis) =
        consumeLots(state.lots, closingQty)
      val closingProceeds = Money.cost(closingQty, trade.price)
      val realizedDelta =
        if (state.isLong) closingProceeds - closingCostBasis
        else closingCostBasis - closingProceeds

      if (trade.quantity <= available) {
        // Pure close: remaining lots carry the remaining cost basis.
        val remainingCost = remainingLots
          .map(l => BigDecimal(l.quantity) * l.price)
          .foldLeft(BigDecimal(0))(_ + _)
        val signedRemainingCost =
          if (state.isLong) Money.money(remainingCost)
          else -Money.money(remainingCost)
        state.copy(
          quantity = state.quantity + Side.sign(trade.side) * trade.quantity,
          totalCost = signedRemainingCost,
          lots = remainingLots,
          realizedPnl = Money.money(state.realizedPnl + realizedDelta),
          tradeCount = state.tradeCount + 1,
          lastTradeTime = now,
          version = state.version + 1
        )
      } else {
        // Flip: excess opens a new position at the trade price.
        val excessQty = trade.quantity - available
        val newSignedQty = Side.sign(trade.side) * excessQty
        val newCost = trade.side match {
          case Buy  => Money.cost(excessQty, trade.price)
          case Sell => -Money.cost(excessQty, trade.price)
        }
        state.copy(
          quantity = newSignedQty,
          totalCost = newCost,
          lots = List(Lot(excessQty, trade.price)),
          realizedPnl = Money.money(state.realizedPnl + realizedDelta),
          tradeCount = state.tradeCount + 1,
          lastTradeTime = now,
          version = state.version + 1
        )
      }
    }
  }
}
