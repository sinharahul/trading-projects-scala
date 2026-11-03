package risk

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import risk.domain.Fixtures
import risk.exposure.ExposureCalculator
import risk.limits.{InMemoryAlertQueue, LimitChecker, Severity}
import risk.varx.VaRResult

class LimitSpec extends AnyFlatSpec with Matchers {
  private val (exp, _) = ExposureCalculator.compute(
    Fixtures.bookA1, Fixtures.prices, Fixtures.infos)
  // VaR 99% from Step 2: 1,040 dollars, threshold return -0.7274%.
  private val var99 = VaRResult(0.99, 1, -0.007274, BigDecimal(1040),
    -0.007347, java.time.LocalDate.parse("2026-09-03"), 20)

  "LimitChecker" should "find exactly the three expected soft breaches" in {
    val breaches = LimitChecker.evaluate("DESK-1", exp, var99)
    breaches.map(_.limitName).sorted shouldBe
      List("concentration", "gross-exposure", "net-exposure")
    breaches.foreach(_.severity shouldBe Severity.Warning)
  }

  it should "report no breach for a small, diversified book" in {
    val small = List(
      risk.domain.Position("A9", "DESK-9", "ACME", BigDecimal(10), BigDecimal(100)),
      risk.domain.Position("A9", "DESK-9", "GLOB", BigDecimal(10), BigDecimal(80))
    )
    val prices = Map("ACME" -> BigDecimal(120), "GLOB" -> BigDecimal(90))
    val (smallExp, _) = ExposureCalculator.compute(small, prices, Fixtures.infos)
    LimitChecker.evaluate("DESK-9", smallExp, var99) shouldBe empty
  }

  it should "escalate a hard breach as a new alert" in {
    val queue = new InMemoryAlertQueue
    val soft = LimitChecker.evaluate("DESK-1", exp, var99)
    val active = LimitChecker.publishNewBreaches(soft, queue, Set.empty, 1000L)
    queue.size shouldBe 3
    // Same breaches again: suppressed, no new alerts.
    LimitChecker.publishNewBreaches(soft, queue, active, 2000L)
    queue.size shouldBe 3
    // Now force a hard breach by doubling the book.
    val doubled = Fixtures.bookA1.map(p => p.copy(quantity = p.quantity * 2))
    val (bigExp, _) = ExposureCalculator.compute(
      doubled, Fixtures.prices, Fixtures.infos)
    val hard = LimitChecker.evaluate("DESK-1", bigExp, var99)
    hard.exists(_.severity == Severity.Critical) shouldBe true
    LimitChecker.publishNewBreaches(hard, queue, active, 3000L)
    queue.size should be > 3 // hard alerts are new, even for known limits
  }

  it should "clear a breach key once the breach cures" in {
    val queue = new InMemoryAlertQueue
    val breaches = LimitChecker.evaluate("DESK-1", exp, var99)
    val active = LimitChecker.publishNewBreaches(breaches, queue, Set.empty, 1000L)
    val cleared = LimitChecker.publishNewBreaches(Nil, queue, active, 2000L)
    cleared shouldBe empty
  }
}
