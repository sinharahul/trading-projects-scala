package com.backoffice.capture.model

/** One failed validation rule, with enough detail for operations to act. */
case class Rejection(
  code: String,
  detail: String
)

object Rejection {
  def mandatoryField(field: String): Rejection =
    Rejection("MANDATORY_FIELD_MISSING", s"Required field is missing or blank: $field")

  def invalidQuantity(qty: Long): Rejection =
    Rejection("INVALID_QUANTITY", s"Quantity must be positive and <= 10,000,000, got: $qty")

  def invalidPrice(px: BigDecimal): Rejection =
    Rejection("INVALID_PRICE", s"Price must be positive, got: $px")

  def unknownSymbol(symbol: String): Rejection =
    Rejection("UNKNOWN_SYMBOL", s"Symbol not found in security master: $symbol")

  def delistedSymbol(symbol: String): Rejection =
    Rejection("DELISTED_SYMBOL", s"Symbol is not ACTIVE in security master: $symbol")

  def priceOutOfTolerance(px: BigDecimal, ref: BigDecimal, pct: Double): Rejection =
    Rejection("PRICE_OUT_OF_TOLERANCE",
      s"Price $px deviates more than $pct%% from reference price $ref")

  def duplicateExecId(execId: String): Rejection =
    Rejection("DUPLICATE_EXEC_ID", s"ExecID already booked: $execId")

  def outsideTradingHours(symbol: String, time: String): Rejection =
    Rejection("OUTSIDE_TRADING_HOURS", s"Execution for $symbol at $time is outside market hours or on a non-trading day")

  def counterpartyNotApproved(cp: String): Rejection =
    Rejection("COUNTERPARTY_NOT_APPROVED", s"Counterparty is not APPROVED: $cp")

  def referenceDataMissing(symbol: String): Rejection =
    Rejection("REFERENCE_DATA_MISSING", s"No security master row for symbol: $symbol")
}
