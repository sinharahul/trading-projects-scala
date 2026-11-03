package com.backoffice.eod.ledger

/** One ledger line. Exactly one of debitCents and creditCents
  * must be non-zero; the constructor enforces it. */
final case class LedgerLine(
  businessDate: String,
  accountKey: Long,
  entryId: String,
  accountCode: String,
  debitCents: Long,
  creditCents: Long,
  reference: String,
  narrative: String
) {
  require(
    (debitCents == 0) != (creditCents == 0),
    s"Ledger line $entryId must have exactly one of debit/credit non-zero")
  require(debitCents >= 0 && creditCents >= 0,
    s"Ledger line $entryId amounts must be non-negative")
}

object ChartOfAccounts {
  val Cash = "1000"
  val SecuritiesInventory = "1200"
  val SecuritiesRevaluation = "1210"
  val SecuritiesSoldShort = "1300"
  val RealizedPnl = "4000"
  val UnrealizedPnl = "4010"
  val CommissionExpense = "5000"
  val FeeExpense = "5010"
}

object LedgerPoster {
  private def dr(date: String, acc: Long, id: String, code: String,
    amount: Long, ref: String, note: String): LedgerLine =
    LedgerLine(date, acc, id, code, amount, 0L, ref, note)
  private def cr(date: String, acc: Long, id: String, code: String,
    amount: Long, ref: String, note: String): LedgerLine =
    LedgerLine(date, acc, id, code, 0L, amount, ref, note)

  /** Posts a buy: securities in, cash out, plus expense lines. */
  def postBuy(date: String, acc: Long, tradeId: String,
    amount: Long, commission: Long, fees: Long): Seq[LedgerLine] = Seq(
    dr(date, acc, s"$tradeId-1", ChartOfAccounts.SecuritiesInventory,
      amount, tradeId, "Buy securities at cost"),
    cr(date, acc, s"$tradeId-2", ChartOfAccounts.Cash,
      amount, tradeId, "Cash paid for buy"),
    dr(date, acc, s"$tradeId-3", ChartOfAccounts.CommissionExpense,
      commission, tradeId, "Commission on buy"),
    dr(date, acc, s"$tradeId-4", ChartOfAccounts.FeeExpense,
      fees, tradeId, "Exchange and regulatory fees on buy"),
    cr(date, acc, s"$tradeId-5", ChartOfAccounts.Cash,
      commission + fees, tradeId, "Cash paid for commission and fees")
  ).filter(l => l.debitCents != 0 || l.creditCents != 0)

  /** Posts a sale, splitting it into a closing portion and, when
    * the sale exceeds the long position, a short-opening portion.
    * The short portion credits the short liability account, never
    * Realized P&L: the firm owes those shares back.
    *
    * @param proceeds gross sale proceeds
    * @param costBasis cost basis of the closing portion (zero when
    *                  the sale opens a short from flat)
    * @param shortProceeds proceeds attributable to the short portion
    *                      (zero for an ordinary closing sale)
    */
  def postSell(date: String, acc: Long, tradeId: String,
    proceeds: Long, costBasis: Long, shortProceeds: Long,
    commission: Long, fees: Long): Seq[LedgerLine] = {
    val closingProceeds = proceeds - shortProceeds
    val gain = closingProceeds - costBasis
    val gainLines =
      if (gain == 0) Seq.empty
      else if (gain > 0)
        Seq(cr(date, acc, s"$tradeId-3", ChartOfAccounts.RealizedPnl,
          gain, tradeId, "Realized gain on sale"))
      else
        Seq(dr(date, acc, s"$tradeId-3", ChartOfAccounts.RealizedPnl,
          -gain, tradeId, "Realized loss on sale"))
    val costLines =
      if (costBasis == 0) Seq.empty
      else Seq(cr(date, acc, s"$tradeId-2",
        ChartOfAccounts.SecuritiesInventory, costBasis, tradeId,
        "Cost basis of shares sold"))
    val shortLines =
      if (shortProceeds == 0) Seq.empty
      else Seq(cr(date, acc, s"$tradeId-7",
        ChartOfAccounts.SecuritiesSoldShort, shortProceeds, tradeId,
        "Proceeds of short sale held as liability"))
    (Seq(
      dr(date, acc, s"$tradeId-1", ChartOfAccounts.Cash,
        proceeds, tradeId, "Cash received for sale")
    ) ++ costLines ++ gainLines ++ shortLines ++ Seq(
      dr(date, acc, s"$tradeId-4", ChartOfAccounts.CommissionExpense,
        commission, tradeId, "Commission on sale"),
      dr(date, acc, s"$tradeId-5", ChartOfAccounts.FeeExpense,
        fees, tradeId, "Exchange and regulatory fees on sale"),
      cr(date, acc, s"$tradeId-6", ChartOfAccounts.Cash,
        commission + fees, tradeId, "Cash paid for commission and fees")
    )).filter(l => l.debitCents != 0 || l.creditCents != 0)
  }

  /** Posts the day's unrealized change for one account-security. */
  def postUnrealized(date: String, acc: Long, secKey: Long,
    change: Long): Seq[LedgerLine] = {
    if (change == 0) Seq.empty
    else if (change > 0) Seq(
      dr(date, acc, s"UNREAL-$secKey-1",
        ChartOfAccounts.SecuritiesRevaluation, change, "UNREALIZED",
        "Mark to market gain"),
      cr(date, acc, s"UNREAL-$secKey-2",
        ChartOfAccounts.UnrealizedPnl, change, "UNREALIZED",
        "Unrealized gain recognized"))
    else Seq(
      dr(date, acc, s"UNREAL-$secKey-1",
        ChartOfAccounts.UnrealizedPnl, -change, "UNREALIZED",
        "Unrealized loss recognized"),
      cr(date, acc, s"UNREAL-$secKey-2",
        ChartOfAccounts.SecuritiesRevaluation, -change, "UNREALIZED",
        "Mark to market loss"))
  }

  /** The balanced-books check. Throws PermanentFailure unless
    * total debits equal total credits, overall and per account. */
  def checkBalanced(lines: Seq[LedgerLine]): Unit = {
    val totalDr = lines.map(_.debitCents).sum
    val totalCr = lines.map(_.creditCents).sum
    if (totalDr != totalCr)
      throw new com.backoffice.eod.model.PermanentFailure(
        s"LEDGER IMBALANCE: debits $totalDr != credits $totalCr, " +
          s"difference ${totalDr - totalCr} cents")
    lines.groupBy(_.accountKey).foreach { case (acc, ls) =>
      val d = ls.map(_.debitCents).sum
      val c = ls.map(_.creditCents).sum
      if (d != c)
        throw new com.backoffice.eod.model.PermanentFailure(
          s"LEDGER IMBALANCE for account $acc: debits $d != credits $c")
    }
  }
}
