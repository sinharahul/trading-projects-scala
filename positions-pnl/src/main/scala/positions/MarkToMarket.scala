package positions

/** Mark-to-market enrichment: pure functions over a position and a price. */
object MarkToMarket {

  /** Market value of a position at the given price. */
  def marketValue(position: PositionState, marketPrice: BigDecimal): BigDecimal =
    Money.money(BigDecimal(position.quantity) * marketPrice)

  /** Unrealized P&L at the given price. Signed formula: works for
    * long and short positions without branches.
    */
  def unrealizedPnl(position: PositionState, marketPrice: BigDecimal): BigDecimal =
    Money.money(
      BigDecimal(position.quantity) * (marketPrice - position.averageCost))

  /** Full snapshot enrichment. A missing price yields None: the caller
    * must decide how to report an unpriced position, never silently
    * substituting zero.
    */
  def enrich(
    position: PositionState,
    marketPriceOpt: Option[BigDecimal],
    snapshotTime: java.sql.Timestamp,
    snapshotType: String
  ): Option[PositionSnapshot] = marketPriceOpt.map { marketPrice =>
    val mv = marketValue(position, marketPrice)
    val unrealized = unrealizedPnl(position, marketPrice)
    PositionSnapshot(
      account = position.account,
      symbol = position.symbol,
      snapshotTime = snapshotTime,
      quantity = position.quantity,
      averageCost = position.averageCost,
      marketPrice = marketPrice,
      marketValue = mv,
      unrealizedPnl = unrealized,
      realizedPnl = position.realizedPnl,
      totalPnl = Money.money(position.realizedPnl + unrealized),
      snapshotType = snapshotType
    )
  }
}
