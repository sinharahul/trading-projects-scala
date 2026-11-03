package com.backoffice.capture.validation

import com.backoffice.capture.model._
import java.time.{Instant, LocalDate, LocalTime, ZoneId}

/**
 * Everything a rule needs, supplied by the caller.
 * In production this is built from Postgres; in tests it is built by hand.
 */
case class SecurityInfo(
  symbol: String,
  exchange: String,
  currency: String,
  referencePx: BigDecimal,
  status: String
)

case class ValidationContext(
  securities: Map[String, SecurityInfo],
  approvedCounterparties: Set[String],
  suspendedCounterparties: Set[String],
  tradingDays: Map[(String, LocalDate), (LocalTime, LocalTime)],
  existingExecIds: Set[String],
  priceTolerancePct: Double,
  maxQuantity: Long
)

object Rules {

  type Rule = (RawExecution, ValidationContext) => Option[Rejection]

  /** Rule 1: every field the pipeline needs must be present and non-blank. */
  val mandatoryFields: Rule = (raw, _) => {
    val missing = List(
      ("execId", raw.execId),
      ("orderId", raw.orderId),
      ("symbol", raw.symbol),
      ("senderCompId", raw.senderCompId)
    ).collect { case (name, value) if value.trim.isEmpty => name }
    missing.headOption.map(f => Rejection.mandatoryField(f))
  }

  /** Rule 2a: quantity must be positive and within the configured maximum. */
  val quantitySanity: Rule = (raw, ctx) => {
    if (raw.lastQty <= 0 || raw.lastQty > ctx.maxQuantity)
      Some(Rejection.invalidQuantity(raw.lastQty))
    else None
  }

  /** Rule 2b: price must be positive. A zero or negative price is never valid. */
  val priceSanity: Rule = (raw, _) => {
    if (raw.lastPx <= 0) Some(Rejection.invalidPrice(raw.lastPx))
    else None
  }

  /** Rule 3: the same ExecID must never book twice. */
  val duplicateCheck: Rule = (raw, ctx) => {
    if (ctx.existingExecIds.contains(raw.execId))
      Some(Rejection.duplicateExecId(raw.execId))
    else None
  }

  /** Rule 4a: the symbol must exist in the security master. */
  val symbolExists: Rule = (raw, ctx) => {
    if (!ctx.securities.contains(raw.symbol))
      Some(Rejection.unknownSymbol(raw.symbol))
    else None
  }

  /** Rule 4b: the symbol must be ACTIVE, not delisted or suspended. */
  val symbolActive: Rule = (raw, ctx) => {
    ctx.securities.get(raw.symbol) match {
      case Some(info) if info.status != "ACTIVE" =>
        Some(Rejection.delistedSymbol(raw.symbol))
      case _ => None
    }
  }

  /** Rule 5: price must be within tolerance of the reference price. */
  val priceTolerance: Rule = (raw, ctx) => {
    ctx.securities.get(raw.symbol) match {
      case None => None // symbolExists already reported this; do not double-report
      case Some(info) if info.referencePx == 0 => None
      case Some(info) =>
        val deviation = ((raw.lastPx - info.referencePx).abs / info.referencePx) * 100
        if (deviation > BigDecimal(ctx.priceTolerancePct))
          Some(Rejection.priceOutOfTolerance(raw.lastPx, info.referencePx, ctx.priceTolerancePct))
        else None
    }
  }

  /** Rule 6: the sending counterparty must be approved, not suspended. */
  val counterpartyApproved: Rule = (raw, ctx) => {
    if (ctx.suspendedCounterparties.contains(raw.senderCompId))
      Some(Rejection.counterpartyNotApproved(raw.senderCompId))
    else if (!ctx.approvedCounterparties.contains(raw.senderCompId))
      Some(Rejection.counterpartyNotApproved(raw.senderCompId))
    else None
  }

  /**
   * Rule 7: the execution must fall inside market hours on a trading day.
   *
   * Timezone trap, handled explicitly: TransactTime arrives in UTC.
   * The exchange session is defined in the exchange's local timezone.
   * Convert first, compare second. Never compare a UTC instant against
   * local session hours directly — that is how a 10:32 AM New York trade
   * gets rejected, or a 2:00 AM trade gets accepted.
   */
  val tradingHours: Rule = (raw, ctx) => {
    ctx.securities.get(raw.symbol) match {
      case None => None // symbolExists already reported this
      case Some(info) =>
        val zone = ZoneId.of(exchangeZone(info.exchange))
        val localDateTime = raw.transactTime.atZone(zone).toLocalDateTime
        val date = localDateTime.toLocalDate
        val time = localDateTime.toLocalTime
        ctx.tradingDays.get((info.exchange, date)) match {
          case None =>
            Some(Rejection.outsideTradingHours(raw.symbol,
              s"$date $time ${zone.getId} (non-trading day)"))
          case Some((open, close)) =>
            if (time.isBefore(open) || time.isAfter(close))
              Some(Rejection.outsideTradingHours(raw.symbol,
                s"$date $time ${zone.getId} (session $open-$close)"))
            else None
        }
    }
  }

  private def exchangeZone(exchange: String): String = exchange match {
    case "NASDAQ" | "NYSE" => "America/New_York"
    case "LSE" => "Europe/London"
    case "TSE" => "Asia/Tokyo"
    case _ => "America/New_York"
  }

  /** The rules, in the order they run. */
  val all: List[(String, Rule)] = List(
    "mandatoryFields" -> mandatoryFields,
    "quantitySanity" -> quantitySanity,
    "priceSanity" -> priceSanity,
    "duplicateCheck" -> duplicateCheck,
    "symbolExists" -> symbolExists,
    "symbolActive" -> symbolActive,
    "priceTolerance" -> priceTolerance,
    "counterpartyApproved" -> counterpartyApproved,
    "tradingHours" -> tradingHours
  )
}
