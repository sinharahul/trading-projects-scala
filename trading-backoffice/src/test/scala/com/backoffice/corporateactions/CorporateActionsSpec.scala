package com.backoffice.corporateactions

import java.time.LocalDate
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CorporateActionsSpec extends AnyFlatSpec with Matchers {

  private def date(s: String): LocalDate = LocalDate.parse(s)

  private def splitAction: CorporateAction = CorporateAction(
    actionId = "CA-2026-1042",
    source = "VENDOR-X",
    symbol = "ACME",
    actionType = ActionType.Split,
    ratioNumerator = BigDecimal(2),
    ratioDenominator = BigDecimal(1),
    effectiveDate = date("2026-11-16"),
    exDate = date("2026-11-16"),
    recordDate = date("2026-11-13"),
    paymentDate = date("2026-11-16")
  )

  "SplitAdjuster" should "double quantity and halve cost basis" in {
    val p = Position("ACC-1", "ACME", BigDecimal(100), BigDecimal(50.00))
    val r = SplitAdjuster.adjust(p, splitAction, FractionalTreatment.RoundDown)
    r.after.quantity shouldBe BigDecimal(200)
    r.after.costBasisPerShare shouldBe BigDecimal("25.00")
    r.after.totalCost shouldBe p.totalCost
  }

  it should "pay cash in lieu for fractional shares" in {
    val threeForTwo = splitAction.copy(
      actionId = "CA-2026-1043",
      ratioNumerator = BigDecimal(3),
      ratioDenominator = BigDecimal(2)
    )
    val p = Position("ACC-2", "ACME", BigDecimal(101), BigDecimal(40.00))
    val r = SplitAdjuster.adjust(
      p, threeForTwo, FractionalTreatment.CashInLieu(BigDecimal("60.00")))
    r.after.quantity shouldBe BigDecimal(151)
    r.cashInLieu shouldBe BigDecimal("30.00")
    r.fractionalShares shouldBe BigDecimal("0.5")
  }

  it should "handle reverse splits on non-divisible holdings" in {
    val reverse = splitAction.copy(
      actionId = "CA-2026-1044",
      actionType = ActionType.ReverseSplit,
      ratioNumerator = BigDecimal(1),
      ratioDenominator = BigDecimal(10)
    )
    val p = Position("ACC-3", "ACME", BigDecimal(1055), BigDecimal(2.00))
    val r = SplitAdjuster.adjust(
      p, reverse, FractionalTreatment.CashInLieu(BigDecimal("25.00")))
    r.after.quantity shouldBe BigDecimal(105)
    r.cashInLieu shouldBe BigDecimal("12.50")
  }

  it should "never adjust settled trades" in {
    val t = Trade("T-9", "ACC-1", "ACME", BigDecimal(100), BigDecimal(50.00),
      "BUY", date("2026-11-10"), date("2026-11-11"), settled = true)
    SplitAdjuster.adjustTrade(t, splitAction) shouldBe t
  }

  "DividendEngine" should "exclude ex-date trades from entitlement" in {
    val dividend = CorporateAction(
      actionId = "CA-2026-1101",
      source = "VENDOR-X",
      symbol = "ACME",
      actionType = ActionType.CashDividend,
      cashPerShare = BigDecimal("0.75"),
      effectiveDate = date("2026-11-12"),
      exDate = date("2026-11-12"),
      recordDate = date("2026-11-13"),
      paymentDate = date("2026-11-30")
    )
    val positions = List(
      Position("ACC-1", "ACME", BigDecimal(1000), BigDecimal(50.00)),
      Position("ACC-2", "ACME", BigDecimal(400), BigDecimal(50.00)),
      Position("ACC-3", "ACME", BigDecimal(800), BigDecimal(50.00))
    )
    val trades = List(
      Trade("T-1", "ACC-2", "ACME", BigDecimal(200), BigDecimal(52.00), "BUY",
        date("2026-11-11"), date("2026-11-16"), settled = false),
      Trade("T-2", "ACC-3", "ACME", BigDecimal(300), BigDecimal(52.00), "BUY",
        date("2026-11-12"), date("2026-11-13"), settled = false)
    )
    val results = DividendEngine.entitlements(dividend, positions, trades)
    val byAccount = results.map(r => r.accountId -> r.grossAmount).toMap
    byAccount("ACC-1") shouldBe BigDecimal("750.00")
    byAccount("ACC-2") shouldBe BigDecimal("450.00")
    byAccount("ACC-3") shouldBe BigDecimal("600.00")
    DividendEngine.totalPayable(results) shouldBe BigDecimal("1800.00")
  }

  "MergerEngine" should "convert a mixed cash and stock merger" in {
    val merger = CorporateAction(
      actionId = "CA-2026-1150",
      source = "VENDOR-X",
      symbol = "ACME",
      actionType = ActionType.Merger,
      cashPerShare = BigDecimal("20.00"),
      stockRatio = Some(BigDecimal("0.5")),
      newSymbol = Some("GLOBEX"),
      effectiveDate = date("2026-12-01"),
      exDate = date("2026-12-01"),
      recordDate = date("2026-11-25"),
      paymentDate = date("2026-12-05")
    )
    val p = Position("ACC-1", "ACME", BigDecimal(1000), BigDecimal(30.00))
    val r = MergerEngine.convert(p, merger, FractionalTreatment.RoundDown)
    r.newPosition.map(_.quantity) shouldBe Some(BigDecimal(500))
    r.newPosition.map(_.costBasisPerShare) shouldBe Some(BigDecimal("20.00"))
    r.cashReceived shouldBe BigDecimal("20000.00")
    r.realisedGain shouldBe BigDecimal(0)
  }

  it should "book a gain on an all-cash merger above cost" in {
    val merger = CorporateAction(
      actionId = "CA-2026-1151",
      source = "VENDOR-X",
      symbol = "ACME",
      actionType = ActionType.Merger,
      cashPerShare = BigDecimal("55.00"),
      newSymbol = None,
      effectiveDate = date("2026-12-01"),
      exDate = date("2026-12-01"),
      recordDate = date("2026-11-25"),
      paymentDate = date("2026-12-05")
    )
    val p = Position("ACC-1", "ACME", BigDecimal(300), BigDecimal(40.00))
    val r = MergerEngine.convert(p, merger, FractionalTreatment.RoundDown)
    r.newPosition shouldBe None
    r.cashReceived shouldBe BigDecimal("16500.00")
    r.realisedGain shouldBe BigDecimal("4500.00")
  }

  "RightsEngine" should "allocate and partially exercise rights" in {
    val rightsAction = CorporateAction(
      actionId = "CA-2026-1180",
      source = "VENDOR-X",
      symbol = "ACME",
      actionType = ActionType.RightsIssue,
      rightsRatio = Some(BigDecimal("0.25")),
      subscriptionPrice = Some(BigDecimal("30.00")),
      newSymbol = Some("ACME-R"),
      effectiveDate = date("2026-12-10"),
      exDate = date("2026-12-10"),
      recordDate = date("2026-12-09"),
      paymentDate = date("2027-01-09")
    )
    val allocations = RightsEngine.allocate(rightsAction, List(
      Position("ACC-1", "ACME", BigDecimal(1000), BigDecimal(50.00))))
    allocations.head.rightsQuantity shouldBe BigDecimal(250)
    val exercised = RightsEngine.exercise(allocations.head, BigDecimal(200))
    exercised.exercised shouldBe BigDecimal(200)
    exercised.expired shouldBe BigDecimal(50)
    exercised.cashPaid shouldBe BigDecimal("6000.00")
  }

  "IdempotentEngine" should "apply a split exactly once across reruns" in {
    val registry = new AppliedActionRegistry
    var book = List(Position("ACC-1", "ACME", BigDecimal(100), BigDecimal(50.00)))
    (1 to 3).foreach { _ =>
      val (newBook, _) = IdempotentEngine.applySplitToBook(
        book, splitAction, FractionalTreatment.RoundDown, registry, "test")
      book = newBook
    }
    book.head.quantity shouldBe BigDecimal(200)
    book.head.costBasisPerShare shouldBe BigDecimal("25.00")
    registry.appliedCount shouldBe 1
  }

  it should "allow a corrected version after the original" in {
    val registry = new AppliedActionRegistry
    val original = Position("ACC-1", "ACME", BigDecimal(100), BigDecimal(50.00))
    val (adjusted, result) = IdempotentEngine.applySplit(
      original, splitAction, FractionalTreatment.RoundDown, registry, "test")
    result shouldBe a[ApplyResult.Applied]
    val corrected = splitAction.copy(
      actionId = "CA-2026-1042-CORR", version = 2,
      supersedes = Some("CA-2026-1042"))
    val (_, result2) = IdempotentEngine.applySplit(
      adjusted, corrected, FractionalTreatment.RoundDown, registry, "test")
    result2 shouldBe a[ApplyResult.Applied]
  }

  "DateEngine" should "classify a late announcement for restatement" in {
    val late = splitAction.copy(effectiveDate = date("2026-11-16"))
    val resolved = DateEngine.resolve(late, date("2026-12-20"))
    resolved.isBackdated shouldBe true
    DateEngine.classifyBackdating(resolved, date("2026-12-20")) shouldBe
      "RESTATEMENT_AND_RECONCILIATION"
  }

  "CorporateActionValidator" should "reject a split with a zero denominator" in {
    val bad = splitAction.copy(ratioDenominator = BigDecimal(0))
    CorporateActionValidator.validate(bad).isLeft shouldBe true
  }
}
