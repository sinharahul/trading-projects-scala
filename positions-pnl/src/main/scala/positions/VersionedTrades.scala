package positions

sealed trait TradeStatus
case object Active extends TradeStatus
case object Cancelled extends TradeStatus
case object Amended extends TradeStatus // superseded by a later version

/** A versioned trade record. Version 1 is the original booking;
  * each amendment increments the version; a cancel marks the
  * latest version cancelled. Only the latest version per trade id
  * participates in position computation.
  */
case class VersionedTrade(
  trade: Trade,
  version: Int,
  status: TradeStatus
)

object VersionedTrades {
  /** Reduce a list of versioned records to the effective trade set:
    * latest version per trade id, excluding cancelled trades.
    * Input order does not matter; output is sorted by trade time so
    * the position fold consumes trades in execution order.
    */
  def effectiveTrades(records: List[VersionedTrade]): List[Trade] = {
    records
      .groupBy(_.trade.tradeId)
      .values
      .map(_.maxBy(_.version))
      .filter(_.status != Cancelled)
      .map(_.trade)
      .toList
      .sortBy(_.tradeTime.getTime)
  }
}
