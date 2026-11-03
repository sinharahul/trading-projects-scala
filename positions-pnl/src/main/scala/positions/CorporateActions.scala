package positions

object CorporateActions {
  /** Apply a stock split to a position. factor = new shares per old share.
    * Example: a 2-for-1 split has factor 2; a 1-for-10 reverse split
    * has factor 0.1. Total cost and realized P&L are unchanged.
    */
  def applySplit(state: PositionState, factor: BigDecimal): PositionState = {
    require(factor > 0, "split factor must be positive")
    val newQty = (BigDecimal(state.quantity) * factor)
      .setScale(0, scala.math.BigDecimal.RoundingMode.DOWN).toLong
    val adjustedLots = state.lots.map { lot =>
      val newLotQty = (BigDecimal(lot.quantity) * factor)
        .setScale(0, scala.math.BigDecimal.RoundingMode.DOWN).toLong
      Lot(newLotQty, Money.price(lot.price / factor))
    }
    state.copy(
      quantity = newQty,
      lots = adjustedLots,
      // totalCost is unchanged by a split; average cost halves because
      // quantity doubles. It recomputes from totalCost / quantity.
      version = state.version + 1
    )
  }
}
