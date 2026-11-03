package recon

import java.time.LocalDate
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EndToEndSpec extends AnyFlatSpec with Matchers {

  "The full pipeline on the sample data" should "produce the Step 8 dashboard" in {
    val calendar = SettlementCalendar.fromResource("us-holidays-2026.txt")
    val ssiRepo = SsiRepository.fromResource("ssi.csv")
    val generator = new InstructionGenerator(calendar, ssiRepo, FeeSchedule.default)

    val trades = Seq(
      Trade("T001", "AAPL", Buy, BigDecimal(100), BigDecimal("175.50"), "USD",
        LocalDate.parse("2026-10-30"), "BROKER-A", "ACC-1001", "BNY"),
      Trade("T002", "MSFT", Sell, BigDecimal(50), BigDecimal("420.00"), "USD",
        LocalDate.parse("2026-10-29"), "BROKER-B", "ACC-1001", "BNY"),
      Trade("T003", "US91282CJL67", Buy, BigDecimal(1000000), BigDecimal("99.25"), "USD",
        LocalDate.parse("2026-11-25"), "BROKER-A", "ACC-1002", "BNY"),
      Trade("T004", "TSLA", Buy, BigDecimal(10), BigDecimal("250.00"), "USD",
        LocalDate.parse("2026-10-30"), "BROKER-A", "ACC-1001", "BNY"),
      Trade("T005", "NVDA", Buy, BigDecimal(20), BigDecimal("180.00"), "USD",
        LocalDate.parse("2026-10-30"), "BROKER-A", "ACC-1001", "BNY")
    )
    val instructions = generator.generateAll(trades)
    val parsed = StatementParser.parseFile("data/custodian-bny-2026-11-02.csv")
    parsed.errors shouldBe empty

    val matcher = new Matcher(MatchTolerances.default(calendar))
    val results = matcher.matchAll(instructions, parsed.entries)
    results.count(_.kind == ExactMatch) shouldBe 3
    results.count(_.kind == FuzzyMatch) shouldBe 1
    results.count(_.kind == UnmatchedInstruction) shouldBe 1
    results.count(_.kind == UnmatchedStatement) shouldBe 3

    val asOf = LocalDate.parse("2026-11-03")
    val breaks = new BreakClassifier(asOf).classify(results, instructions)
    breaks should have size 5
    breaks.count(_.breakType == MissingTrade) shouldBe 1
    breaks.count(_.breakType == DateMismatch) shouldBe 1
    breaks.count(_.breakType == WrongAccount) shouldBe 1
    breaks.count(_.breakType == UnexpectedEntry) shouldBe 2

    val knownFees = Seq(KnownFee("BNY", "USD", BigDecimal("25.00"), "BNY settlement fee"))
    val (resolutions, queue) =
      new ResolutionEngine(knownFees, asOf).resolve(breaks)
    resolutions should have size 2
    queue should have size 3

    val cashReport = CashReconciliation.reconcile(instructions, parsed.entries)
    cashReport.totalDifference shouldBe BigDecimal("-34981.98")

    val dashboard = Dashboard.build(breaks, resolutions, cashReport, asOf)
    dashboard.totalBreaks shouldBe 5
    dashboard.openBreaks shouldBe 3
    dashboard.autoResolutionRate shouldBe 0.4 +- 0.001
  }
}
