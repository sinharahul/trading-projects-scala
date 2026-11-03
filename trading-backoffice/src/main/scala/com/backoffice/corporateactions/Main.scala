package com.backoffice.corporateactions

import java.time.LocalDate

object Main extends App {
  val split = CorporateAction(
    actionId = "CA-2026-1042",
    source = "VENDOR-X",
    symbol = "ACME",
    actionType = ActionType.Split,
    ratioNumerator = BigDecimal(2),
    ratioDenominator = BigDecimal(1),
    effectiveDate = LocalDate.parse("2026-11-16"),
    exDate = LocalDate.parse("2026-11-16"),
    recordDate = LocalDate.parse("2026-11-13"),
    paymentDate = LocalDate.parse("2026-11-16")
  )

  val dividend = CorporateAction(
    actionId = "CA-2026-1101",
    source = "VENDOR-X",
    symbol = "ACME",
    actionType = ActionType.CashDividend,
    cashPerShare = BigDecimal("0.75"),
    effectiveDate = LocalDate.parse("2026-11-12"),
    exDate = LocalDate.parse("2026-11-12"),
    recordDate = LocalDate.parse("2026-11-13"),
    paymentDate = LocalDate.parse("2026-11-30")
  )

  val registry = new AppliedActionRegistry
  var book = List(Position("ACC-1", "ACME", BigDecimal(100), BigDecimal(50.00)))

  val (afterSplit, splitAudits) = IdempotentEngine.applySplitToBook(
    book, split, FractionalTreatment.RoundDown, registry, "demo")
  book = afterSplit
  println(s"after split: ${book.head.quantity} @ ${book.head.costBasisPerShare}")

  val entitlements = DividendEngine.entitlements(dividend, book, List.empty)
  entitlements.foreach { e =>
    println(s"dividend: ${e.accountId} entitled ${e.entitledQuantity} = ${e.grossAmount}")
  }

  println(s"audit records: ${splitAudits.size}")
  splitAudits.foreach { a =>
    println(s"  ${a.actionId}: ${a.quantityBefore} -> ${a.quantityAfter}, " +
      s"basis ${a.costBasisBefore} -> ${a.costBasisAfter}")
  }
}
