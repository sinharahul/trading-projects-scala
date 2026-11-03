package recon

import java.time.LocalDate
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GeneratorSpec extends AnyFlatSpec with Matchers {

  private val calendar = new SettlementCalendar(Set(
    LocalDate.parse("2026-11-26")))
  private val ssiRepo = new SsiRepository(Seq(
    Ssi("SSI-BNY-USD-EQ", "BNY", "USD", "EQUITY", "ACC-1001")))
  private val generator =
    new InstructionGenerator(calendar, ssiRepo, FeeSchedule.default)

  "InstructionGenerator" should "compute buy cash as gross plus commission" in {
    val trade = Trade("T100", "AAPL", Buy, BigDecimal(100),
      BigDecimal("175.50"), "USD", LocalDate.parse("2026-10-30"),
      "BROKER-A", "ACC-1001", "BNY")
    val inst = generator.generate(trade)
    inst.cashAmount shouldBe BigDecimal("17558.78")
    inst.settlementDate shouldBe LocalDate.parse("2026-11-02")
    inst.account shouldBe "ACC-1001"
    inst.instructionId shouldBe "SI-T100"
  }

  it should "compute sell cash as gross minus commission, signed negative" in {
    val trade = Trade("T101", "MSFT", Sell, BigDecimal(50),
      BigDecimal("420.00"), "USD", LocalDate.parse("2026-10-29"),
      "BROKER-B", "ACC-1001", "BNY")
    val inst = generator.generate(trade)
    inst.cashAmount shouldBe BigDecimal("-20989.50")
  }

  it should "apply the minimum fee for tiny trades" in {
    // Gross 100.00 at 5 bps is 0.05, below the 1.00 minimum.
    val trade = Trade("T102", "AAPL", Buy, BigDecimal(1),
      BigDecimal("100.00"), "USD", LocalDate.parse("2026-10-30"),
      "BROKER-A", "ACC-1001", "BNY")
    generator.generate(trade).cashAmount shouldBe BigDecimal("101.00")
  }

  it should "derive the instruction id from the trade id deterministically" in {
    val trade = Trade("T103", "AAPL", Buy, BigDecimal(10),
      BigDecimal("100.00"), "USD", LocalDate.parse("2026-10-30"),
      "BROKER-A", "ACC-1001", "BNY")
    generator.generate(trade).instructionId shouldBe
      generator.generate(trade).instructionId
  }
}
