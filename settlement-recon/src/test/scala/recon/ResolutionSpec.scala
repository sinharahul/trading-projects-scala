package recon

import java.time.LocalDate
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ResolutionSpec extends AnyFlatSpec with Matchers {

  private val asOf = LocalDate.parse("2026-11-03")

  private def breakOf(
    id: String,
    breakType: BreakType,
    ourCash: Option[BigDecimal] = None,
    theirCash: Option[BigDecimal] = None,
    detected: LocalDate = asOf
  ): Break = Break(id, Some("T1"), Some("STMT-1"), breakType, Medium,
    Some(BigDecimal(100)), Some(BigDecimal(100)), ourCash, theirCash,
    Some(LocalDate.parse("2026-11-02")), Some(LocalDate.parse("2026-11-02")),
    "ACC-1001", "BNY", "USD", detected, "test break")

  "ResolutionEngine" should "auto-resolve date mismatches" in {
    val engine = new ResolutionEngine(Nil, asOf)
    val (resolved, queue) = engine.resolve(Seq(breakOf("BRK-1", DateMismatch)))
    resolved should have size 1
    queue shouldBe empty
  }

  it should "auto-resolve an amount mismatch explained by a known fee" in {
    val engine = new ResolutionEngine(
      Seq(KnownFee("BNY", "USD", BigDecimal("25.00"), "BNY settlement fee")), asOf)
    val b = breakOf("BRK-1", AmountMismatch,
      Some(BigDecimal("-17558.78")), Some(BigDecimal("-17583.78")))
    val (resolved, queue) = engine.resolve(Seq(b))
    resolved should have size 1
    resolved.head.reason should include("BNY settlement fee")
    queue shouldBe empty
  }

  it should "queue an amount mismatch that matches no known fee" in {
    val engine = new ResolutionEngine(
      Seq(KnownFee("BNY", "USD", BigDecimal("25.00"), "BNY settlement fee")), asOf)
    val b = breakOf("BRK-1", AmountMismatch,
      Some(BigDecimal("-17558.78")), Some(BigDecimal("-17558.75")))
    val (resolved, queue) = engine.resolve(Seq(b))
    resolved shouldBe empty
    queue should have size 1
    queue.head.assignedTeam shouldBe "Finance / Fees"
  }

  it should "never auto-resolve missing trades or unexpected entries" in {
    val engine = new ResolutionEngine(Nil, asOf)
    val (resolved, queue) = engine.resolve(Seq(
      breakOf("BRK-1", MissingTrade),
      breakOf("BRK-2", UnexpectedEntry)))
    resolved shouldBe empty
    queue should have size 2
  }

  "AgingReport" should "bucket breaks by calendar age" in {
    val breaks = Seq(
      breakOf("BRK-1", MissingTrade, detected = LocalDate.parse("2026-11-03")),
      breakOf("BRK-2", MissingTrade, detected = LocalDate.parse("2026-10-30")),
      breakOf("BRK-3", MissingTrade, detected = LocalDate.parse("2026-09-01"))
    )
    val report = AgingReport.build(breaks, asOf)
    report.totalOpen shouldBe 3
    report.buckets.find(_.label == "0-1 days").get.count shouldBe 1
    report.buckets.find(_.label == "2-5 days").get.count shouldBe 1
    report.buckets.find(_.label == "Over 30 days").get.count shouldBe 1
  }

  "CashReconciliation" should "balance when expected equals actual" in {
    val inst = SettlementInstruction("SI-T1", "T1", "AAPL", Buy,
      BigDecimal(100), BigDecimal("17558.78"), "USD",
      LocalDate.parse("2026-11-02"), "ACC-1001", "BNY",
      "SSI-BNY-USD-EQ", Instructed)
    val entry = StatementEntry("STMT-1", "BNY", "ACC-1001", "AAPL",
      BigDecimal(100), BigDecimal("-17558.78"), "USD",
      LocalDate.parse("2026-11-02"), "CUST-1", "SETTLEMENT")
    val report = CashReconciliation.reconcile(Seq(inst), Seq(entry))
    report.unbalanced shouldBe empty
    report.totalDifference shouldBe BigDecimal(0)
  }

  it should "surface a fee line as a cash difference" in {
    val inst = SettlementInstruction("SI-T1", "T1", "AAPL", Buy,
      BigDecimal(100), BigDecimal("17558.78"), "USD",
      LocalDate.parse("2026-11-02"), "ACC-1001", "BNY",
      "SSI-BNY-USD-EQ", Instructed)
    val settlement = StatementEntry("STMT-1", "BNY", "ACC-1001", "AAPL",
      BigDecimal(100), BigDecimal("-17558.78"), "USD",
      LocalDate.parse("2026-11-02"), "CUST-1", "SETTLEMENT")
    val fee = StatementEntry("STMT-2", "BNY", "ACC-1001", "AAPL",
      BigDecimal(0), BigDecimal("-25.00"), "USD",
      LocalDate.parse("2026-11-02"), "CUST-2", "FEE")
    val report = CashReconciliation.reconcile(Seq(inst), Seq(settlement, fee))
    report.unbalanced should have size 1
    report.totalDifference shouldBe BigDecimal("-25.00")
  }
}
