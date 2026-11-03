package recon

import java.time.LocalDate

/** Side of a trade, from our firm's perspective. */
sealed trait Side
case object Buy extends Side
case object Sell extends Side

/** A booked trade in our own books. */
case class Trade(
  tradeId: String,
  instrument: String,          // e.g. "AAPL", "US91282CJL67"
  side: Side,
  quantity: BigDecimal,
  price: BigDecimal,
  currency: String,            // settlement currency, e.g. "USD"
  tradeDate: LocalDate,
  counterparty: String,
  account: String,             // our custody account
  custodian: String            // which custodian holds the account
) {
  /** Gross consideration before fees and taxes. */
  def grossAmount: BigDecimal = quantity * price
}

/** One leg of a delivery-versus-payment settlement. */
sealed trait SettlementLeg
case object ReceiveSecurities extends SettlementLeg
case object DeliverSecurities extends SettlementLeg

/** What we instruct the custodian to do. */
case class SettlementInstruction(
  instructionId: String,
  tradeId: String,
  instrument: String,
  side: Side,
  quantity: BigDecimal,
  cashAmount: BigDecimal,      // signed: positive = we pay, negative = we receive
  currency: String,
  settlementDate: LocalDate,
  account: String,
  custodian: String,
  ssiId: String,               // standing settlement instruction used
  status: InstructionStatus
)

sealed trait InstructionStatus
case object Instructed extends InstructionStatus
case object Settled extends InstructionStatus
case object Failed extends InstructionStatus
case object Cancelled extends InstructionStatus

/** One line from a custodian statement file. */
case class StatementEntry(
  statementId: String,
  custodian: String,
  account: String,
  instrument: String,
  quantity: BigDecimal,
  cashAmount: BigDecimal,      // signed from OUR perspective: positive = cash in
  currency: String,
  valueDate: LocalDate,        // date the custodian applied the movement
  reference: String,           // custodian's reference, may differ from ours
  entryType: String            // "SETTLEMENT", "FEE", "TAX", "CASH"
)

/** A classified difference between our books and the custodian. */
case class Break(
  breakId: String,
  tradeId: Option[String],
  statementId: Option[String],
  breakType: BreakType,
  severity: Severity,
  ourQuantity: Option[BigDecimal],
  theirQuantity: Option[BigDecimal],
  ourCash: Option[BigDecimal],
  theirCash: Option[BigDecimal],
  ourDate: Option[LocalDate],
  theirDate: Option[LocalDate],
  account: String,
  custodian: String,
  currency: String,
  detectedDate: LocalDate,
  description: String
)

sealed trait BreakType
case object MissingTrade extends BreakType
case object AmountMismatch extends BreakType
case object DateMismatch extends BreakType
case object WrongAccount extends BreakType
case object UnexpectedEntry extends BreakType

sealed trait Severity
case object Low extends Severity
case object Medium extends Severity
case object High extends Severity

/** What happened to a break. */
case class Resolution(
  breakId: String,
  outcome: ResolutionOutcome,
  reason: String,
  resolvedDate: Option[LocalDate]
)

sealed trait ResolutionOutcome
case object AutoResolved extends ResolutionOutcome
case object QueuedManual extends ResolutionOutcome
