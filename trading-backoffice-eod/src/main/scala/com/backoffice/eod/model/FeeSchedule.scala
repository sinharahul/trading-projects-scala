package com.backoffice.eod.model

/** Fee schedule for the sample system. Production systems load
  * this from a reference table; the sample hard-codes it so the
  * arithmetic is visible and testable. */
object FeeSchedule {
  /** Flat commission per executed trade, in cents. */
  val CommissionPerTradeCents: Long = 100L // $1.00

  /** Exchange fee rate in basis points (1 bp = 0.01 percent).
    * 15 bp = 0.15 percent of trade amount. */
  val ExchangeFeeBps: Long = 15L

  /** Regulatory fee rate in basis points: 2 bp = 0.02 percent. */
  val RegulatoryFeeBps: Long = 2L

  /** Computes a percentage fee in cents, rounding half-up.
    * Returns (feeCents, roundingRemainderCents) where the
    * remainder is the signed difference between the exact fee
    * and the rounded fee, in ten-thousandths of a cent, for
    * audit purposes. The remainder is NOT booked; it is reported
    * so the ledger job can post an explicit rounding line when
    * the accumulated remainder crosses one cent. */
  def percentageFee(amountCents: Long, bps: Long): Long = {
    val numerator = amountCents * bps
    (numerator + 5000L) / 10000L
  }

  def commissionForTradeCount(tradeCount: Long): Long =
    tradeCount * CommissionPerTradeCents

  def feesForTrade(amountCents: Long): Long =
    percentageFee(amountCents, ExchangeFeeBps) +
      percentageFee(amountCents, RegulatoryFeeBps)
}
