package risk

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import risk.domain.Fixtures
import risk.margin.MarginAccount
import risk.stress.StressEngine

class StressSpec extends AnyFlatSpec with Matchers {
  private val leveraged =
    MarginAccount("A2", BigDecimal(10000), BigDecimal(120000))

  "StressEngine" should "reproduce the hand-computed 2008-style P&L" in {
    val results = StressEngine.runSuite(
      Fixtures.bookA1, Fixtures.prices, Fixtures.infos, leveraged)
    val crisis = results.find(_.scenarioName == "2008-style crisis").get
    crisis.pnl shouldBe BigDecimal(-54600)
    crisis.stressedNetValue shouldBe BigDecimal(88400)
    crisis.stressedMarginCall shouldBe BigDecimal(53985)
  }

  it should "show the flash crash dwarfing 99% VaR" in {
    val results = StressEngine.runSuite(
      Fixtures.bookA1, Fixtures.prices, Fixtures.infos, leveraged)
    val flash = results.find(_.scenarioName.startsWith("Flash crash")).get
    flash.pnl shouldBe BigDecimal(-13070)
    // More than 12x the 99% VaR of 1,040: the report must say so.
    flash.pnl.abs should be > BigDecimal(12 * 1040)
  }

  it should "show the single-name shock as the worst scenario" in {
    val results = StressEngine.runSuite(
      Fixtures.bookA1, Fixtures.prices, Fixtures.infos, leveraged)
    val worst = results.minBy(_.pnl)
    worst.scenarioName shouldBe "Single-name shock: ACME -50%"
    worst.pnl shouldBe BigDecimal(-60000)
  }

  it should "leave unshocked symbols unchanged" in {
    val results = StressEngine.runSuite(
      Fixtures.bookA1, Fixtures.prices, Fixtures.infos, leveraged)
    val single = results.find(_.scenarioName.startsWith("Single-name")).get
    single.stressedPrices("GLOB") shouldBe BigDecimal(90)
    single.stressedPrices("STAB") shouldBe BigDecimal(55)
  }
}
