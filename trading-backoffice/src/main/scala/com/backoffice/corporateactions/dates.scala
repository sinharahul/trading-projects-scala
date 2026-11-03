package com.backoffice.corporateactions

import java.time.LocalDate

/** The resolved timeline for one action, with the backdating
  * classification attached. */
case class ResolvedDates(
  action: CorporateAction,
  entitlementDate: LocalDate,
  adjustmentDate: LocalDate,
  isBackdated: Boolean,
  backdatedReason: Option[String]
)

object DateEngine {

  /** Resolve the dates that drive processing for one action.
    *
    * @param action The action to resolve.
    * @param processingDate The date the engine is running — normally
    *   today. An action is backdated when its effective date is
    *   already past at processing time and the action has not yet
    *   been applied.
    */
  def resolve(
    action: CorporateAction,
    processingDate: LocalDate
  ): ResolvedDates = {
    val (entitlementDate, adjustmentDate) = action.actionType match {
      case ActionType.CashDividend | ActionType.RightsIssue | ActionType.SpinOff =>
        // Entitlement is fixed by the register on the record date.
        (action.recordDate, action.paymentDate)
      case ActionType.Split | ActionType.ReverseSplit | ActionType.StockDividend =>
        // Positions are rewritten on the effective date; the record
        // date determines which positions are in scope.
        (action.recordDate, action.effectiveDate)
      case ActionType.Merger =>
        (action.recordDate, action.effectiveDate)
    }

    val isBackdated = action.effectiveDate.isBefore(processingDate)
    val reason =
      if (isBackdated)
        Some(s"effective date ${action.effectiveDate} is before " +
          s"processing date $processingDate; announcement arrived late " +
          s"or was applied after its effective date")
      else None

    ResolvedDates(action, entitlementDate, adjustmentDate, isBackdated, reason)
  }

  /** Select the positions in scope for an action: positions in the
    * symbol as of the record date. In this tutorial the caller
    * supplies the record-date snapshot; in production this is a
    * point-in-time query against the position history. */
  def positionsInScope(
    positions: List[Position],
    action: CorporateAction
  ): List[Position] =
    positions.filter(_.symbol == action.symbol)

  /** A backdated announcement must not be applied silently to the
    * current book. It requires restatement: the adjustment is
    * computed as of the effective date and then replayed forward
    * through any subsequent trades and actions. This function
    * classifies the backdating severity so the caller can route it. */
  def classifyBackdating(
    resolved: ResolvedDates,
    processingDate: LocalDate
  ): String = {
    if (!resolved.isBackdated) "CURRENT"
    else {
      val daysLate =
        java.time.temporal.ChronoUnit.DAYS.between(
          resolved.action.effectiveDate, processingDate)
      if (daysLate <= 5) "LATE_NOTICE"
      else if (daysLate <= 30) "RESTATEMENT_REQUIRED"
      else "RESTATEMENT_AND_RECONCILIATION"
    }
  }
}
