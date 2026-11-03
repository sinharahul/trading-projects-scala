package com.backoffice.corporateactions

import java.math.RoundingMode

/** How a fractional share resulting from an adjustment is handled. */
sealed trait FractionalTreatment
object FractionalTreatment {
  /** Sell the fraction and pay cash at the given market price. */
  case class CashInLieu(marketPrice: BigDecimal) extends FractionalTreatment
  /** Discard the fraction. */
  case object RoundDown extends FractionalTreatment
  /** Round to the nearest whole share, half-up. */
  case object RoundNearest extends FractionalTreatment
}

/** The result of adjusting one position. Cash is zero unless a
  * cash-in-lieu payment was generated. */
case class AdjustedPosition(
  before: Position,
  after: Position,
  cashInLieu: BigDecimal = BigDecimal(0),
  fractionalShares: BigDecimal = BigDecimal(0)
)

object SplitAdjuster {

  private val QuantityScale = 6
  private val CostScale = 4

  /** Adjust one position for a split or reverse split.
    *
    * The total-cost invariant is preserved up to rounding: the new
    * total cost equals the old total cost, except for any value
    * removed as cash in lieu or lost to rounding, which is reported
    * explicitly in the result so that nothing disappears silently.
    */
  def adjust(
    position: Position,
    action: CorporateAction,
    treatment: FractionalTreatment
  ): AdjustedPosition = {
    require(
      action.actionType == ActionType.Split ||
      action.actionType == ActionType.ReverseSplit,
      s"SplitAdjuster cannot handle ${action.actionType}"
    )
    val multiplier = action.multiplier
    val rawQuantity = position.quantity * multiplier
    val wholeQuantity = rawQuantity.setScale(0, BigDecimal.RoundingMode.FLOOR)
    val fraction = rawQuantity - wholeQuantity

    treatment match {
      case FractionalTreatment.CashInLieu(marketPrice) =>
        val cash = (fraction * marketPrice).setScale(2, BigDecimal.RoundingMode.HALF_UP)
        val newCostPerShare =
          if (wholeQuantity == 0) BigDecimal(0)
          else (position.totalCost - cash) / wholeQuantity
        AdjustedPosition(
          before = position,
          after = position.copy(
            quantity = wholeQuantity,
            costBasisPerShare = newCostPerShare.setScale(CostScale, BigDecimal.RoundingMode.HALF_UP)
          ),
          cashInLieu = cash,
          fractionalShares = fraction
        )
      case FractionalTreatment.RoundDown =>
        val newCostPerShare =
          if (wholeQuantity == 0) BigDecimal(0)
          else position.totalCost / wholeQuantity
        AdjustedPosition(
          before = position,
          after = position.copy(
            quantity = wholeQuantity,
            costBasisPerShare = newCostPerShare.setScale(CostScale, BigDecimal.RoundingMode.HALF_UP)
          ),
          fractionalShares = fraction
        )
      case FractionalTreatment.RoundNearest =>
        val rounded = rawQuantity.setScale(0, BigDecimal.RoundingMode.HALF_UP)
        val newCostPerShare =
          if (rounded == 0) BigDecimal(0)
          else position.totalCost / rounded
        AdjustedPosition(
          before = position,
          after = position.copy(
            quantity = rounded,
            costBasisPerShare = newCostPerShare.setScale(CostScale, BigDecimal.RoundingMode.HALF_UP)
          ),
          fractionalShares = fraction
        )
    }
  }

  /** Adjust an unsettled trade for a split. Settled trades are never
    * rewritten: they are history. The price is divided by the same
    * multiplier that multiplies the quantity, preserving trade value. */
  def adjustTrade(trade: Trade, action: CorporateAction): Trade = {
    if (trade.settled) trade
    else {
      val multiplier = action.multiplier
      trade.copy(
        quantity = trade.quantity * multiplier,
        price = (trade.price / multiplier).setScale(4, BigDecimal.RoundingMode.HALF_UP)
      )
    }
  }
}

/** The result of converting one target position in a merger. */
case class MergerConversion(
  before: Position,
  newPosition: Option[Position],
  cashReceived: BigDecimal,
  realisedGain: BigDecimal,
  fractionalShares: BigDecimal = BigDecimal(0),
  cashInLieu: BigDecimal = BigDecimal(0)
)

object MergerEngine {

  /** Convert one position under a merger action.
    *
    * @param position The target position to convert.
    * @param action   A Merger action with cashPerShare, stockRatio,
    *                 and newSymbol as applicable.
    * @param treatment How fractional acquirer shares are handled.
    */
  def convert(
    position: Position,
    action: CorporateAction,
    treatment: FractionalTreatment = FractionalTreatment.RoundDown
  ): MergerConversion = {
    require(action.actionType == ActionType.Merger,
      s"MergerEngine cannot handle ${action.actionType}")

    val cash = (position.quantity * action.cashPerShare)
      .setScale(2, BigDecimal.RoundingMode.HALF_UP)

    val rawNewShares = action.stockRatio
      .map(r => position.quantity * r)
      .getOrElse(BigDecimal(0))

    val wholeShares = rawNewShares.setScale(0, BigDecimal.RoundingMode.FLOOR)
    val fraction = rawNewShares - wholeShares

    val (finalShares, lieuCash) = treatment match {
      case FractionalTreatment.CashInLieu(marketPrice) =>
        (wholeShares,
         (fraction * marketPrice).setScale(2, BigDecimal.RoundingMode.HALF_UP))
      case _ =>
        (wholeShares, BigDecimal(0))
    }

    val totalCash = cash + lieuCash
    val remainingCost = (position.totalCost - totalCash).max(BigDecimal(0))
    val gain = (totalCash - position.totalCost).max(BigDecimal(0))

    val newPosition =
      if (finalShares > 0 && action.newSymbol.isDefined) {
        val newBasis =
          if (finalShares == 0) BigDecimal(0)
          else (remainingCost / finalShares)
            .setScale(4, BigDecimal.RoundingMode.HALF_UP)
        Some(Position(
          accountId = position.accountId,
          symbol = action.newSymbol.get,
          quantity = finalShares,
          costBasisPerShare = newBasis,
          currency = position.currency
        ))
      } else None

    MergerConversion(
      before = position,
      newPosition = newPosition,
      cashReceived = totalCash,
      realisedGain = gain,
      fractionalShares = fraction,
      cashInLieu = lieuCash
    )
  }

  /** Cancel open orders in a symbol that ceases to trade. In this
    * tutorial, orders are represented as unsettled trades with a
    * side; a production system would cancel working orders in the
    * order book and notify the holders. The function returns the
    * list of cancelled trade IDs so the caller can record them. */
  def cancelOpenOrders(trades: List[Trade], symbol: String): List[String] =
    trades.filter(t => t.symbol == symbol && !t.settled).map(_.tradeId)
}

object StockDividendAdjuster {

  /** A stock dividend is a split-like adjustment: quantity up by the
    * multiplier, cost basis down by the same factor, total cost
    * unchanged. The only difference from a split is the audit label
    * and the tax character, both of which come from the action type. */
  def adjust(
    position: Position,
    action: CorporateAction,
    treatment: FractionalTreatment
  ): AdjustedPosition = {
    require(action.actionType == ActionType.StockDividend,
      s"StockDividendAdjuster cannot handle ${action.actionType}")
    SplitAdjuster.adjust(
      position,
      action.copy(actionType = ActionType.Split),
      treatment
    )
  }
}

/** Spin-off conversion: parent quantity unchanged, child shares
  * distributed by ratio, cost basis allocated by relative fair
  * market value. */
case class SpinOffResult(
  parentAfter: Position,
  childPosition: Option[Position],
  cashInLieu: BigDecimal
)

object SpinOffEngine {

  def convert(
    position: Position,
    action: CorporateAction,
    treatment: FractionalTreatment = FractionalTreatment.RoundDown
  ): SpinOffResult = {
    require(action.actionType == ActionType.SpinOff,
      s"SpinOffEngine cannot handle ${action.actionType}")

    val ratio = action.multiplier // child shares per parent share
    val rawChild = position.quantity * ratio
    val wholeChild = rawChild.setScale(0, BigDecimal.RoundingMode.FLOOR)
    val fraction = rawChild - wholeChild

    val fmvParent = action.spinOffFmvParent.getOrElse(BigDecimal(0))
    val fmvChild = action.spinOffFmvChild.getOrElse(BigDecimal(0))
    val parentValue = position.quantity * fmvParent
    val childValue = wholeChild * fmvChild
    val totalValue = parentValue + childValue

    val parentBasisTotal =
      if (totalValue == 0) position.totalCost
      else position.totalCost * parentValue / totalValue
    val childBasisTotal =
      if (totalValue == 0) BigDecimal(0)
      else position.totalCost * childValue / totalValue

    val (finalChildQty, lieuCash) = treatment match {
      case FractionalTreatment.CashInLieu(marketPrice) =>
        (wholeChild,
         (fraction * marketPrice).setScale(2, BigDecimal.RoundingMode.HALF_UP))
      case _ => (wholeChild, BigDecimal(0))
    }

    val childBasisAfterCash = (childBasisTotal - lieuCash).max(BigDecimal(0))
    val parentAfter = position.copy(
      costBasisPerShare =
        if (position.quantity == 0) BigDecimal(0)
        else (parentBasisTotal / position.quantity)
          .setScale(4, BigDecimal.RoundingMode.HALF_UP)
    )
    val childPosition =
      if (finalChildQty > 0 && action.newSymbol.isDefined)
        Some(Position(
          accountId = position.accountId,
          symbol = action.newSymbol.get,
          quantity = finalChildQty,
          costBasisPerShare =
            if (finalChildQty == 0) BigDecimal(0)
            else (childBasisAfterCash / finalChildQty)
              .setScale(4, BigDecimal.RoundingMode.HALF_UP),
          currency = position.currency
        ))
      else None

    SpinOffResult(parentAfter, childPosition, lieuCash)
  }
}
