package com.backoffice.corporateactions

import java.time.LocalDate

/** One dividend payment owed to one account. */
case class DividendEntitlement(
  accountId: String,
  symbol: String,
  actionId: String,
  entitledQuantity: BigDecimal,
  dividendPerShare: BigDecimal,
  grossAmount: BigDecimal,
  withholdingTax: BigDecimal = BigDecimal(0),
  netAmount: BigDecimal
)

object DividendEngine {

  /** Compute entitlements for a cash dividend action.
    *
    * @param action The dividend announcement. Must be a CashDividend.
    * @param positions Positions as of the record date.
    * @param trades All trades in the symbol around the dates. Only
    *   unsettled trades executed before the ex-date adjust entitlement.
    * @param withholdingRate Optional withholding tax rate, e.g. 0.15
    *   for 15%. Zero means no withholding.
    */
  def entitlements(
    action: CorporateAction,
    positions: List[Position],
    trades: List[Trade],
    withholdingRate: BigDecimal = BigDecimal(0)
  ): List[DividendEntitlement] = {
    require(action.actionType == ActionType.CashDividend,
      s"DividendEngine cannot handle ${action.actionType}")

    val byAccount = positions
      .filter(_.symbol == action.symbol)
      .groupBy(_.accountId)

    byAccount.toList.map { case (accountId, accountPositions) =>
      val settledQuantity =
        accountPositions.map(_.quantity).sum

      val pendingAdjustment = trades
        .filter(t => t.accountId == accountId && t.symbol == action.symbol)
        .filter(t => !t.settled)
        .filter(t => t.tradeDate.isBefore(action.exDate))
        .map { t =>
          if (t.side == "BUY") t.quantity else -t.quantity
        }.sum

      val entitled = settledQuantity + pendingAdjustment
      val gross = (entitled * action.cashPerShare)
        .setScale(2, BigDecimal.RoundingMode.HALF_UP)
      val tax = (gross * withholdingRate)
        .setScale(2, BigDecimal.RoundingMode.HALF_UP)

      DividendEntitlement(
        accountId = accountId,
        symbol = action.symbol,
        actionId = action.actionId,
        entitledQuantity = entitled,
        dividendPerShare = action.cashPerShare,
        grossAmount = gross,
        withholdingTax = tax,
        netAmount = gross - tax
      )
    }.filter(_.entitledQuantity > 0)
  }

  /** Total cash payable for the dividend, before withholding. */
  def totalPayable(entitlements: List[DividendEntitlement]): BigDecimal =
    entitlements.map(_.grossAmount).sum
}

/** A rights position: the temporary instrument created by a rights issue. */
case class RightsPosition(
  accountId: String,
  symbol: String, // the underlying symbol
  rightsSymbol: String, // the temporary rights instrument
  rightsQuantity: BigDecimal,
  subscriptionPrice: BigDecimal,
  expiryDate: java.time.LocalDate
)

/** The result of exercising some or all of a rights position. */
case class RightsExerciseResult(
  accountId: String,
  exercised: BigDecimal,
  expired: BigDecimal,
  cashPaid: BigDecimal,
  newShares: BigDecimal
)

object RightsEngine {

  /** Allocate rights to holders in proportion to their position. */
  def allocate(
    action: CorporateAction,
    positions: List[Position]
  ): List[RightsPosition] = {
    require(action.actionType == ActionType.RightsIssue,
      s"RightsEngine cannot handle ${action.actionType}")
    val ratio = action.rightsRatio.getOrElse(BigDecimal(0))
    val price = action.subscriptionPrice.getOrElse(BigDecimal(0))
    positions
      .filter(_.symbol == action.symbol)
      .map { p =>
        RightsPosition(
          accountId = p.accountId,
          symbol = p.symbol,
          rightsSymbol = action.newSymbol.getOrElse(s"${p.symbol}-R"),
          rightsQuantity = (p.quantity * ratio)
            .setScale(0, BigDecimal.RoundingMode.FLOOR),
          subscriptionPrice = price,
          expiryDate = action.paymentDate
        )
      }
      .filter(_.rightsQuantity > 0)
  }

  /** Exercise some rights and expire the rest. Exercised rights
    * convert into shares at the subscription price; the caller adds
    * the new shares to the position and books the cash paid. */
  def exercise(
    rights: RightsPosition,
    exerciseQuantity: BigDecimal
  ): RightsExerciseResult = {
    val exercised = exerciseQuantity.min(rights.rightsQuantity).max(BigDecimal(0))
    val expired = rights.rightsQuantity - exercised
    RightsExerciseResult(
      accountId = rights.accountId,
      exercised = exercised,
      expired = expired,
      cashPaid = (exercised * rights.subscriptionPrice)
        .setScale(2, BigDecimal.RoundingMode.HALF_UP),
      newShares = exercised
    )
  }
}
