package recon

import java.time.LocalDate
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ClassifierSpec extends AnyFlatSpec with Matchers {

  private val asOf = LocalDate.parse("2026-11-03")
  private val classifier = new BreakClassifier(asOf)

  private def inst(tradeId: String = "T1"): SettlementInstruction =
    SettlementInstruction(s"SI-$tradeId", tradeId, "AAPL", Buy,
      BigDecimal(100), BigDecimal("17558.78"), "USD",
      LocalDate.parse("2026-11-02"), "ACC-1001", "BNY",
      "SSI-BNY-USD-EQ", Instructed)

  private def stmt(id: String = "STMT-1"): StatementEntry =
    StatementEntry(id, "BNY", "ACC-1001", "AAPL", BigDecimal(100),
      BigDecimal("-17558.78"), "USD", LocalDate.parse("2026-11-02"),
      "CUST-1", "SETTLEMENT")

  "BreakClassifier" should "produce no breaks for exact matches" in {
    val result = MatchResult(Some(inst()), Some(stmt()), ExactMatch,
      Some(BigDecimal(0)), Some(0), "All key fields agree")
    classifier.classify(Seq(result), Seq(inst())) shouldBe empty
  }

  it should "produce an AmountMismatch for a fuzzy cash difference" in {
    val result = MatchResult(Some(inst()), Some(stmt()), FuzzyMatch,
      Some(BigDecimal("0.03")), Some(0), "cash differs by 0.03")
    val breaks = classifier.classify(Seq(result), Seq(inst()))
    breaks should have size 1
    breaks.head.breakType shouldBe AmountMismatch
    breaks.head.severity shouldBe Low
  }

  it should "produce a DateMismatch for a fuzzy date difference" in {
    val late = stmt().copy(valueDate = LocalDate.parse("2026-11-03"))
    val result = MatchResult(Some(inst()), Some(late), FuzzyMatch,
      Some(BigDecimal(0)), Some(1), "date differs by 1 business days")
    val breaks = classifier.classify(Seq(result), Seq(inst()))
    breaks should have size 1
    breaks.head.breakType shouldBe DateMismatch
    breaks.head.severity shouldBe Medium
  }

  it should "produce both breaks when cash and date both differ" in {
    val late = stmt().copy(valueDate = LocalDate.parse("2026-11-03"),
      cashAmount = BigDecimal("-17558.75"))
    val result = MatchResult(Some(inst()), Some(late), FuzzyMatch,
      Some(BigDecimal("0.03")), Some(1), "cash differs by 0.03; date differs by 1 business days")
    val breaks = classifier.classify(Seq(result), Seq(inst()))
    breaks.map(_.breakType) should contain theSameElementsAs
      Seq(AmountMismatch, DateMismatch)
  }

  it should "produce a High-severity MissingTrade when overdue" in {
    val result = MatchResult(Some(inst()), None, UnmatchedInstruction,
      None, None, "No statement entry matched this instruction")
    val breaks = classifier.classify(Seq(result), Seq(inst()))
    breaks should have size 1
    breaks.head.breakType shouldBe MissingTrade
    breaks.head.severity shouldBe High
  }

  it should "produce a Medium-severity MissingTrade when settlement is today" in {
    val todayInst = inst().copy(settlementDate = asOf)
    val result = MatchResult(Some(todayInst), None, UnmatchedInstruction,
      None, None, "No statement entry matched this instruction")
    val breaks = classifier.classify(Seq(result), Seq(todayInst))
    breaks.head.severity shouldBe Medium
  }

  it should "produce WrongAccount when only the account differs" in {
    val wrongAcct = stmt().copy(account = "ACC-1002")
    val result = MatchResult(None, Some(wrongAcct), UnmatchedStatement,
      None, None, "No instruction matched this statement entry")
    val breaks = classifier.classify(Seq(result), Seq(inst()))
    breaks should have size 1
    breaks.head.breakType shouldBe WrongAccount
    breaks.head.tradeId shouldBe Some("T1")
  }

  it should "produce UnexpectedEntry when nothing explains the statement line" in {
    val unknown = stmt().copy(instrument = "GOOGL", statementId = "STMT-9")
    val result = MatchResult(None, Some(unknown), UnmatchedStatement,
      None, None, "No instruction matched this statement entry")
    val breaks = classifier.classify(Seq(result), Seq(inst()))
    breaks should have size 1
    breaks.head.breakType shouldBe UnexpectedEntry
    breaks.head.tradeId shouldBe None
  }
}
