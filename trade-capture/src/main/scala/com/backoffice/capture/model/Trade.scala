package com.backoffice.capture.model

import java.time.{Instant, LocalDate}

/** Side of a trade. Buy = 1, Sell = 2 in FIX tag 54. */
sealed trait Side
object Side {
  case object Buy extends Side
  case object Sell extends Side

  def fromFix(code: String): Either[String, Side] = code match {
    case "1" => Right(Buy)
    case "2" => Right(Sell)
    case other => Left(s"Unknown side code: $other")
  }
}

/** What kind of execution event this is (FIX tag 150). */
sealed trait ExecType
object ExecType {
  case object Trade extends ExecType        // F: new fill
  case object TradeCorrect extends ExecType // G: amendment of a prior fill
  case object TradeCancel extends ExecType  // H: cancellation (bust) of a prior fill

  def fromFix(code: String): Either[String, ExecType] = code match {
    case "F" => Right(Trade)
    case "G" => Right(TradeCorrect)
    case "H" => Right(TradeCancel)
    case other => Left(s"Unsupported ExecType: $other")
  }
}

/**
 * The parsed form of one execution report, before validation.
 *
 * Money is BigDecimal, never Double. A Double cannot represent 227.45
 * exactly; it represents something very close to 227.45, and the tiny
 * error compounds across millions of trades into real cents. BigDecimal
 * is exact decimal arithmetic, which is what money requires.
 */
case class RawExecution(
  execId: String,
  orderId: String,
  symbol: String,
  side: Side,
  execType: ExecType,
  orderQty: Long,
  lastQty: Long,
  lastPx: BigDecimal,
  transactTime: Instant,
  senderCompId: String,
  msgSeqNum: Long
)

/** A trade that passed every validation rule, before enrichment. */
case class Trade(
  execId: String,
  orderId: String,
  symbol: String,
  side: Side,
  execType: ExecType,
  quantity: Long,
  price: BigDecimal,
  tradeDate: LocalDate,
  counterparty: String
)

/** A trade enriched with reference data, ready to book. */
case class EnrichedTrade(
  trade: Trade,
  exchange: String,
  currency: String,
  notional: BigDecimal,
  settlementDate: LocalDate
)
