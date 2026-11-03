package com.trading.reporting

/** A single validation violation, precise enough to act on. */
case class Violation(
  reportId: String,
  field: String,
  rule: String,
  message: String
)

/** The outcome of validating one report. */
sealed trait ValidationResult
object ValidationResult {
  case class Valid(reportId: String) extends ValidationResult
  case class Invalid(reportId: String, violations: List[Violation]) extends ValidationResult
}

object Validators {

  private def mandatory(
    reportId: String,
    field: String,
    value: String
  ): Option[Violation] =
    if (value == null || value.trim.isEmpty)
      Some(Violation(reportId, field, "MANDATORY", s"$field is required but missing"))
    else None

  private def positiveQuantity(
    reportId: String,
    quantity: Long
  ): Option[Violation] =
    if (quantity <= 0)
      Some(Violation(reportId, "quantity", "POSITIVE", s"quantity must be positive, got $quantity"))
    else None

  private def positivePrice(
    reportId: String,
    price: BigDecimal
  ): Option[Violation] =
    if (price <= 0)
      Some(Violation(reportId, "price", "POSITIVE", s"price must be positive, got $price"))
    else None

  private def priceScale(
    reportId: String,
    price: BigDecimal
  ): Option[Violation] =
    if (price.scale > 8)
      Some(Violation(reportId, "price", "SCALE", s"price scale exceeds 8 decimals: $price"))
    else None

  private def identifierFormat(
    reportId: String,
    field: String,
    value: String
  ): Option[Violation] = {
    val legal = value.matches("[A-Za-z0-9\\-|_]+")
    if (!legal)
      Some(Violation(reportId, field, "FORMAT", s"$field contains illegal characters: $value"))
    else None
  }

  /** Validate one CAT record. Returns all violations, not just the first. */
  def validateCat(report: CatReport): ValidationResult = {
    val id = report.linkageKey
    val violations = List(
      mandatory(id, "firmId", report.firmId),
      mandatory(id, "orderId", report.orderId),
      mandatory(id, "eventType", report.eventType),
      mandatory(id, "symbol", report.symbol),
      mandatory(id, "side", report.side),
      mandatory(id, "linkageKey", report.linkageKey),
      identifierFormat(id, "orderId", report.orderId),
      positiveQuantity(id, report.quantity),
      report.price.flatMap(p => positivePrice(id, p)),
      report.price.flatMap(p => priceScale(id, p)),
      // Cross-field: an execution event must carry a price.
      if (report.eventType == "OrderExecuted" && report.price.isEmpty)
        Some(Violation(id, "price", "CROSS_FIELD",
          "execution event requires a price"))
      else None,
      // Cross-field: a routing event must not carry a price.
      if (report.eventType == "OrderRouted" && report.price.isDefined)
        Some(Violation(id, "price", "CROSS_FIELD",
          "routing event must not carry a price"))
      else None,
      // Cross-field: side codes are a closed set.
      if (!Set("B", "S", "SS").contains(report.side))
        Some(Violation(id, "side", "FORMAT", s"unknown side code ${report.side}"))
      else None
    ).flatten
    if (violations.isEmpty) ValidationResult.Valid(id)
    else ValidationResult.Invalid(id, violations)
  }

  /** Validate one MiFIR record. */
  def validateMifir(report: MifirReport): ValidationResult = {
    val id = report.transactionReferenceNumber
    val violations = List(
      mandatory(id, "transactionReferenceNumber", report.transactionReferenceNumber),
      mandatory(id, "tradingVenue", report.tradingVenue),
      mandatory(id, "instrumentIdentification", report.instrumentIdentification),
      mandatory(id, "buyerIdentification", report.buyerIdentification),
      mandatory(id, "sellerIdentification", report.sellerIdentification),
      mandatory(id, "investmentDecisionMaker", report.investmentDecisionMaker),
      mandatory(id, "executingPerson", report.executingPerson),
      identifierFormat(id, "transactionReferenceNumber", report.transactionReferenceNumber),
      positiveQuantity(id, report.quantity),
      positivePrice(id, report.price),
      priceScale(id, report.price),
      // Cross-field: buyer and seller must differ.
      if (report.buyerIdentification == report.sellerIdentification)
        Some(Violation(id, "buyerIdentification", "CROSS_FIELD",
          "buyer and seller must not be identical"))
      else None,
      // Cross-field: short-selling indicator must be a known value.
      if (!Set("SESH", "NOSE").contains(report.shortSellingIndicator))
        Some(Violation(id, "shortSellingIndicator", "FORMAT",
          s"unknown short-selling indicator ${report.shortSellingIndicator}"))
      else None,
      // Cross-field: waiver indicator must be a known value.
      if (!Set("true", "false").contains(report.waiverIndicator))
        Some(Violation(id, "waiverIndicator", "FORMAT",
          s"unknown waiver indicator ${report.waiverIndicator}"))
      else None
    ).flatten
    if (violations.isEmpty) ValidationResult.Valid(id)
    else ValidationResult.Invalid(id, violations)
  }

  /** Partition a batch into valid reports and violations. */
  def partitionCat(reports: List[CatReport]): (List[CatReport], List[Violation]) = {
    val results = reports.map(r => (r, validateCat(r)))
    val valid = results.collect { case (r, ValidationResult.Valid(_)) => r }
    val violations = results.collect {
      case (_, ValidationResult.Invalid(_, vs)) => vs
    }.flatten
    (valid, violations)
  }

  def partitionMifir(reports: List[MifirReport]): (List[MifirReport], List[Violation]) = {
    val results = reports.map(r => (r, validateMifir(r)))
    val valid = results.collect { case (r, ValidationResult.Valid(_)) => r }
    val violations = results.collect {
      case (_, ValidationResult.Invalid(_, vs)) => vs
    }.flatten
    (valid, violations)
  }
}
