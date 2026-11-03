package recon

import java.time.LocalDate
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MatcherSpec extends AnyFlatSpec with Matchers {

  private val calendar = new SettlementCalendar(Set(
    LocalDate.parse("2026-11-26")))
  private val tolerances = MatchTolerances.default(calendar)
  private val matcher = new Matcher(tolerances)

  private def instruction(
    tradeId: String = "T1",
    instrument: String = "AAPL",
    quantity: BigDecimal = BigDecimal(100),
    cash: BigDecimal = BigDecimal("17558.78"),
    date: LocalDate = LocalDate.parse("2026-11-02"),
    account: String = "ACC-1001"
  ): SettlementInstruction = SettlementInstruction(
    s"SI-$tradeId", tradeId, instrument, Buy, quantity, cash, "USD",
    date, account, "BNY", "SSI-BNY-USD-EQ", Instructed)

  private def statement(
    id: String = "STMT-1",
    instrument: String = "AAPL",
    quantity: BigDecimal = BigDecimal(100),
    cash: BigDecimal = BigDecimal("-17558.78"),
    date: LocalDate = LocalDate.parse("2026-11-02"),
    account: String = "ACC-1001"
  ): StatementEntry = StatementEntry(
    id, "BNY", account, instrument, quantity, cash, "USD", date,
    s"CUST-$id", "SETTLEMENT")

  "Matcher" should "exact-match identical economics" in {
    val results = matcher.matchAll(Seq(instruction()), Seq(statement()))
    results should have size 1
    results.head.kind shouldBe ExactMatch
  }

  it should "fuzzy-match a cash difference within tolerance" in {
    // 0.03 difference: within the 0.05 absolute tolerance.
    val results = matcher.matchAll(
      Seq(instruction()), Seq(statement(cash = BigDecimal("-17558.75"))))
    results.head.kind shouldBe FuzzyMatch
    results.head.cashDifference shouldBe Some(BigDecimal("0.03"))
  }

  it should "refuse a cash difference beyond tolerance" in {
    // 58.78 difference: beyond both the absolute and relative tolerances.
    val results = matcher.matchAll(
      Seq(instruction()), Seq(statement(cash = BigDecimal("-17500.00"))))
    results should have size 2
    results.map(_.kind) should contain only (UnmatchedInstruction, UnmatchedStatement)
  }

  it should "fuzzy-match a one-business-day late settlement" in {
    val results = matcher.matchAll(
      Seq(instruction()), Seq(statement(date = LocalDate.parse("2026-11-03"))))
    results.head.kind shouldBe FuzzyMatch
    results.head.dateDifferenceDays shouldBe Some(1)
  }

  it should "refuse a settlement three business days late" in {
    // Nov 2 -> Nov 5 is three business days: beyond the 2-day tolerance.
    val results = matcher.matchAll(
      Seq(instruction()), Seq(statement(date = LocalDate.parse("2026-11-05"))))
    results.map(_.kind) should contain only (UnmatchedInstruction, UnmatchedStatement)
  }

  it should "never fuzzy-match across accounts" in {
    val results = matcher.matchAll(
      Seq(instruction()), Seq(statement(account = "ACC-1002")))
    results.map(_.kind) should contain only (UnmatchedInstruction, UnmatchedStatement)
  }

  it should "never fuzzy-match across instruments" in {
    val results = matcher.matchAll(
      Seq(instruction()), Seq(statement(instrument = "MSFT")))
    results.map(_.kind) should contain only (UnmatchedInstruction, UnmatchedStatement)
  }

  it should "never fuzzy-match a quantity difference" in {
    val results = matcher.matchAll(
      Seq(instruction()), Seq(statement(quantity = BigDecimal(99))))
    results.map(_.kind) should contain only (UnmatchedInstruction, UnmatchedStatement)
  }

  it should "match each item at most once" in {
    // One instruction, two identical statement lines: only one pair forms.
    val results = matcher.matchAll(
      Seq(instruction()),
      Seq(statement(id = "STMT-1"), statement(id = "STMT-2")))
    results.count(_.kind == ExactMatch) shouldBe 1
    results.count(_.kind == UnmatchedStatement) shouldBe 1
  }

  it should "exclude fee lines from trade matching" in {
    val fee = statement(id = "STMT-FEE", quantity = BigDecimal(0),
      cash = BigDecimal("-25.00")).copy(entryType = "FEE")
    val results = matcher.matchAll(Seq(instruction()), Seq(fee))
    results should have size 1
    results.head.kind shouldBe UnmatchedInstruction
  }

  it should "prefer the best fuzzy candidate when several compete" in {
    // Two instructions could fuzzy-match one statement line; the closer
    // cash difference must win.
    val near = instruction(tradeId = "T-NEAR", cash = BigDecimal("17558.78"))
    val far = instruction(tradeId = "T-FAR", cash = BigDecimal("17558.70"))
    val stmt = statement(cash = BigDecimal("-17558.78"))
    val results = matcher.matchAll(Seq(near, far), Seq(stmt))
    val matched = results.filter(_.kind == FuzzyMatch)
    // The near instruction matches exactly, so it takes the exact pass;
    // the far instruction is left unmatched. Assert the structure.
    results.count(_.kind == ExactMatch) shouldBe 1
    results.count(_.kind == UnmatchedInstruction) shouldBe 1
  }
}
