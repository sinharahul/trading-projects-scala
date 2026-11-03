package com.backoffice.corporateactions

import java.time.{LocalDate, LocalDateTime}

/** One auditable adjustment. Every field is required: an audit
  * record with a missing field is worse than no record, because it
  * looks complete while being unverifiable. */
case class AuditRecord(
  auditId: String,
  actionId: String,
  actionType: ActionType,
  accountId: String,
  symbol: String,
  newSymbol: Option[String],
  quantityBefore: BigDecimal,
  quantityAfter: BigDecimal,
  costBasisBefore: BigDecimal,
  costBasisAfter: BigDecimal,
  cashAmount: BigDecimal,
  fractionalShares: BigDecimal,
  ratioApplied: BigDecimal,
  effectiveDate: LocalDate,
  appliedAt: LocalDateTime,
  appliedBy: String,
  notes: String
)

object AuditWriter {

  /** Build an audit record for a split or stock dividend adjustment. */
  def forSplitAdjustment(
    auditId: String,
    action: CorporateAction,
    result: AdjustedPosition,
    appliedBy: String,
    notes: String = ""
  ): AuditRecord = AuditRecord(
    auditId = auditId,
    actionId = action.actionId,
    actionType = action.actionType,
    accountId = result.before.accountId,
    symbol = result.before.symbol,
    newSymbol = None,
    quantityBefore = result.before.quantity,
    quantityAfter = result.after.quantity,
    costBasisBefore = result.before.costBasisPerShare,
    costBasisAfter = result.after.costBasisPerShare,
    cashAmount = result.cashInLieu,
    fractionalShares = result.fractionalShares,
    ratioApplied = action.multiplier,
    effectiveDate = action.effectiveDate,
    appliedAt = LocalDateTime.now(),
    appliedBy = appliedBy,
    notes = notes
  )

  /** Build an audit record for a dividend entitlement. */
  def forDividend(
    auditId: String,
    action: CorporateAction,
    entitlement: DividendEntitlement,
    appliedBy: String
  ): AuditRecord = AuditRecord(
    auditId = auditId,
    actionId = action.actionId,
    actionType = action.actionType,
    accountId = entitlement.accountId,
    symbol = entitlement.symbol,
    newSymbol = None,
    quantityBefore = entitlement.entitledQuantity,
    quantityAfter = entitlement.entitledQuantity,
    costBasisBefore = BigDecimal(0),
    costBasisAfter = BigDecimal(0),
    cashAmount = entitlement.netAmount,
    fractionalShares = BigDecimal(0),
    ratioApplied = entitlement.dividendPerShare,
    effectiveDate = action.paymentDate,
    appliedAt = LocalDateTime.now(),
    appliedBy = appliedBy,
    notes = s"gross ${entitlement.grossAmount}, withholding ${entitlement.withholdingTax}"
  )

  /** Build an audit record for a merger conversion. */
  def forMerger(
    auditId: String,
    action: CorporateAction,
    conversion: MergerConversion,
    appliedBy: String
  ): AuditRecord = AuditRecord(
    auditId = auditId,
    actionId = action.actionId,
    actionType = action.actionType,
    accountId = conversion.before.accountId,
    symbol = conversion.before.symbol,
    newSymbol = conversion.newPosition.map(_.symbol),
    quantityBefore = conversion.before.quantity,
    quantityAfter = conversion.newPosition.map(_.quantity).getOrElse(BigDecimal(0)),
    costBasisBefore = conversion.before.costBasisPerShare,
    costBasisAfter = conversion.newPosition.map(_.costBasisPerShare).getOrElse(BigDecimal(0)),
    cashAmount = conversion.cashReceived,
    fractionalShares = conversion.fractionalShares,
    ratioApplied = action.stockRatio.getOrElse(BigDecimal(0)),
    effectiveDate = action.effectiveDate,
    appliedAt = LocalDateTime.now(),
    appliedBy = appliedBy,
    notes = s"realised gain ${conversion.realisedGain}"
  )
}

object AuditQuery {

  /** All adjustments for one account and symbol, in application order. */
  def forAccountSymbol(
    records: List[AuditRecord],
    accountId: String,
    symbol: String
  ): List[AuditRecord] =
    records
      .filter(r => r.accountId == accountId && r.symbol == symbol)
      .sortBy(_.appliedAt)

  /** All adjustments caused by one announcement, across all accounts.
    * This is the query an auditor runs when an announcement is
    * questioned: it shows the full blast radius. */
  def forAnnouncement(
    records: List[AuditRecord],
    actionId: String
  ): List[AuditRecord] =
    records.filter(_.actionId == actionId)

  /** Reconstruct a position's history: start from the current
    * position and walk the audit records backwards. In production
    * this is a point-in-time query; here the list form shows the
    * shape of the answer. */
  def positionHistory(
    records: List[AuditRecord],
    accountId: String,
    symbol: String
  ): List[(LocalDate, BigDecimal, BigDecimal)] =
    forAccountSymbol(records, accountId, symbol).map { r =>
      (r.effectiveDate, r.quantityAfter, r.costBasisAfter)
    }
}
