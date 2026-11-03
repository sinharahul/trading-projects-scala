package recon

import java.time.LocalDate

/** Fee and tax schedule applied at instruction generation. */
case class FeeSchedule(
  commissionRate: BigDecimal,   // e.g. 0.0005 = 5 bps
  taxRate: BigDecimal,          // e.g. 0.0 for US equities in this project
  minimumFee: BigDecimal
) {
  def commission(gross: BigDecimal): BigDecimal = {
    val raw = (gross * commissionRate).setScale(2, BigDecimal.RoundingMode.HALF_UP)
    raw.max(minimumFee)
  }
  def tax(gross: BigDecimal): BigDecimal =
    (gross * taxRate).setScale(2, BigDecimal.RoundingMode.HALF_UP)
}

object FeeSchedule {
  val default: FeeSchedule =
    FeeSchedule(commissionRate = BigDecimal("0.0005"), taxRate = BigDecimal(0), minimumFee = BigDecimal("1.00"))
}

/** Generates settlement instructions from booked trades. */
class InstructionGenerator(
  calendar: SettlementCalendar,
  ssiRepo: SsiRepository,
  feeSchedule: FeeSchedule,
  settlementCycleDays: Int = 1
) {

  /** Market is derived from the instrument for this project: ISIN-like
    * instruments (12 chars, starting with a country code) are fixed income,
    * short tickers are equities. Production systems use reference data. */
  def marketFor(instrument: String): String =
    if (instrument.length == 12 && instrument.take(2).forall(_.isLetter)) "FIXED_INCOME"
    else "EQUITY"

  def generate(trade: Trade): SettlementInstruction = {
    val market = marketFor(trade.instrument)
    val ssi = ssiRepo.resolve(trade.custodian, trade.currency, market)
    val settlementDate = calendar.addBusinessDays(trade.tradeDate, settlementCycleDays)

    val gross = trade.grossAmount
    val commission = feeSchedule.commission(gross)
    val tax = feeSchedule.tax(gross)
    // For a buy we pay gross + fees; for a sell we receive gross - fees.
    // The instruction cash amount is signed positive = we pay.
    val cashAmount = trade.side match {
      case Buy  => gross + commission + tax
      case Sell => -(gross - commission - tax)
    }

    SettlementInstruction(
      instructionId = s"SI-${trade.tradeId}",
      tradeId = trade.tradeId,
      instrument = trade.instrument,
      side = trade.side,
      quantity = trade.quantity,
      cashAmount = cashAmount.setScale(2, BigDecimal.RoundingMode.HALF_UP),
      currency = trade.currency,
      settlementDate = settlementDate,
      account = ssi.account,
      custodian = trade.custodian,
      ssiId = ssi.ssiId,
      status = Instructed
    )
  }

  def generateAll(trades: Seq[Trade]): Seq[SettlementInstruction] =
    trades.map(generate)
}
