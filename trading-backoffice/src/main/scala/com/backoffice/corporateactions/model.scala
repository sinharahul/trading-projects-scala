package com.backoffice.corporateactions

import java.time.LocalDate

/** The seven action types this processor handles. */
sealed trait ActionType
object ActionType {
  case object Split extends ActionType
  case object ReverseSplit extends ActionType
  case object CashDividend extends ActionType
  case object StockDividend extends ActionType
  case object Merger extends ActionType
  case object RightsIssue extends ActionType
  case object SpinOff extends ActionType
}

/** A corporate action announcement, normalised into canonical form.
  *
  * @param actionId   Unique ID of this announcement version.
  * @param source     Feed or vendor that supplied the announcement.
  * @param symbol     The affected instrument.
  * @param actionType What kind of action this is.
  * @param ratioNumerator / ratioDenominator  For splits and stock
  *   dividends: new shares per old shares, as a fraction. A 2-for-1
  *   split is 2/1. A 5% stock dividend is 105/100, expressed as the
  *   multiplier on the holding (1.05). Prefer the multiplier form so
  *   that every quantity adjustment is a single multiplication.
  * @param cashPerShare  For cash dividends and the cash leg of a
  *   merger: amount of cash per old share, in the settlement currency.
  * @param newSymbol   For mergers and spin-offs: the symbol of the
  *   resulting position. None for actions that keep the same symbol.
  * @param stockRatio  For mergers: new shares of the acquirer per old
  *   share of the target. None for non-merger actions.
  * @param subscriptionPrice For rights issues: the price at which
  *   rights may be exercised.
  * @param rightsRatio For rights issues: new shares purchasable per
  *   old share held (for example 1/4 means one new share per four held).
  * @param spinOffFmvParent / spinOffFmvChild  For spin-offs: the fair
  *   market values used to allocate cost basis between parent and child.
  * @param effectiveDate The date the action takes effect in the market.
  * @param exDate        First date the shares trade without the action.
  * @param recordDate    Date the register is read for entitlement.
  * @param paymentDate   Date cash or new shares are delivered.
  * @param version       Announcement version; corrections increment it.
  * @param supersedes    The actionId this version replaces, if any.
  */
case class CorporateAction(
  actionId: String,
  source: String,
  symbol: String,
  actionType: ActionType,
  ratioNumerator: BigDecimal = BigDecimal(1),
  ratioDenominator: BigDecimal = BigDecimal(1),
  cashPerShare: BigDecimal = BigDecimal(0),
  newSymbol: Option[String] = None,
  stockRatio: Option[BigDecimal] = None,
  subscriptionPrice: Option[BigDecimal] = None,
  rightsRatio: Option[BigDecimal] = None,
  spinOffFmvParent: Option[BigDecimal] = None,
  spinOffFmvChild: Option[BigDecimal] = None,
  effectiveDate: LocalDate,
  exDate: LocalDate,
  recordDate: LocalDate,
  paymentDate: LocalDate,
  version: Int = 1,
  supersedes: Option[String] = None
) {
  /** The multiplier applied to a holding quantity. */
  def multiplier: BigDecimal =
    if (ratioDenominator == 0) BigDecimal(1)
    else ratioNumerator / ratioDenominator
}

/** A holding of one symbol in one account. Cost basis is per share. */
case class Position(
  accountId: String,
  symbol: String,
  quantity: BigDecimal,
  costBasisPerShare: BigDecimal,
  currency: String = "USD"
) {
  def totalCost: BigDecimal = quantity * costBasisPerShare
}

/** An executed trade. Only unsettled trades are adjusted; settled
  * trades are history and must never be rewritten. */
case class Trade(
  tradeId: String,
  accountId: String,
  symbol: String,
  quantity: BigDecimal,
  price: BigDecimal,
  side: String, // "BUY" or "SELL"
  tradeDate: LocalDate,
  settlementDate: LocalDate,
  settled: Boolean = false
)

object CorporateActionValidator {

  sealed trait ValidationError { def message: String }
  case class SimpleError(message: String) extends ValidationError

  def validate(action: CorporateAction): Either[List[ValidationError], CorporateAction] = {
    val errors = List.newBuilder[ValidationError]

    if (action.actionId.trim.isEmpty)
      errors += SimpleError("actionId must not be empty")
    if (action.symbol.trim.isEmpty)
      errors += SimpleError("symbol must not be empty")
    if (action.ratioDenominator == 0)
      errors += SimpleError("ratioDenominator must not be zero")

    action.actionType match {
      case ActionType.Split | ActionType.ReverseSplit =>
        if (action.multiplier <= 0)
          errors += SimpleError("split multiplier must be positive")
      case ActionType.CashDividend =>
        if (action.cashPerShare < 0)
          errors += SimpleError("dividend cashPerShare must not be negative")
      case ActionType.StockDividend =>
        if (action.multiplier <= 1)
          errors += SimpleError("stock dividend multiplier must exceed 1")
      case ActionType.Merger =>
        if (action.newSymbol.isEmpty && action.cashPerShare == 0)
          errors += SimpleError("merger needs a newSymbol, a cash component, or both")
        if (action.stockRatio.exists(_ < 0))
          errors += SimpleError("merger stockRatio must not be negative")
      case ActionType.RightsIssue =>
        if (action.rightsRatio.isEmpty || action.subscriptionPrice.isEmpty)
          errors += SimpleError("rights issue needs rightsRatio and subscriptionPrice")
      case ActionType.SpinOff =>
        if (action.newSymbol.isEmpty)
          errors += SimpleError("spin-off needs a newSymbol for the distributed shares")
        if (action.spinOffFmvParent.isEmpty || action.spinOffFmvChild.isEmpty)
          errors += SimpleError("spin-off needs fair market values for basis allocation")
    }

    if (!action.exDate.isAfter(action.recordDate) && action.actionType == ActionType.CashDividend) {
      // Note: ex-date is normally one business day before record date
      // in US markets, but the rule is market-dependent, so this is a
      // warning-class check in production. Here we keep it strict for
      // the tutorial so that date mistakes surface early.
      errors += SimpleError("cash dividend exDate should precede recordDate")
    }

    val errs = errors.result()
    if (errs.isEmpty) Right(action) else Left(errs)
  }
}

/** A raw announcement record as delivered by a feed, before validation. */
case class RawAnnouncement(fields: Map[String, String])

object CorporateActionParser {

  def parse(raw: RawAnnouncement): Either[List[CorporateActionValidator.ValidationError], CorporateAction] = {
    val f = raw.fields
    def date(key: String): LocalDate = LocalDate.parse(f(key))
    def dec(key: String, default: String = "0"): BigDecimal = BigDecimal(f.getOrElse(key, default))

    val actionType = f("actionType") match {
      case "SPLIT"          => ActionType.Split
      case "REVERSE_SPLIT"  => ActionType.ReverseSplit
      case "CASH_DIVIDEND"  => ActionType.CashDividend
      case "STOCK_DIVIDEND" => ActionType.StockDividend
      case "MERGER"         => ActionType.Merger
      case "RIGHTS_ISSUE"   => ActionType.RightsIssue
      case "SPIN_OFF"       => ActionType.SpinOff
      case other            => throw new IllegalArgumentException(s"unknown actionType: $other")
    }

    val action = CorporateAction(
      actionId = f("actionId"),
      source = f.getOrElse("source", "UNKNOWN"),
      symbol = f("symbol"),
      actionType = actionType,
      ratioNumerator = dec("ratioNumerator", "1"),
      ratioDenominator = dec("ratioDenominator", "1"),
      cashPerShare = dec("cashPerShare", "0"),
      newSymbol = f.get("newSymbol"),
      stockRatio = f.get("stockRatio").map(BigDecimal(_)),
      subscriptionPrice = f.get("subscriptionPrice").map(BigDecimal(_)),
      rightsRatio = f.get("rightsRatio").map(BigDecimal(_)),
      spinOffFmvParent = f.get("spinOffFmvParent").map(BigDecimal(_)),
      spinOffFmvChild = f.get("spinOffFmvChild").map(BigDecimal(_)),
      effectiveDate = date("effectiveDate"),
      exDate = date("exDate"),
      recordDate = date("recordDate"),
      paymentDate = date("paymentDate"),
      version = f.get("version").map(_.toInt).getOrElse(1),
      supersedes = f.get("supersedes")
    )
    CorporateActionValidator.validate(action)
  }
}
