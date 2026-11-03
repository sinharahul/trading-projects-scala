package risk

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import risk.domain.{Fixtures, PriceTick}
import risk.limits.InMemoryAlertQueue
import risk.streaming.StreamingRiskEngine

class StreamingSpec extends AnyFlatSpec with Matchers {
  "StreamingRiskEngine" should "track the worked tick sequence" in {
    val queue = new InMemoryAlertQueue
    val engine = new StreamingRiskEngine(
      Fixtures.bookA1, Fixtures.prices, Fixtures.infos, queue)
    engine.onTick(PriceTick("ACME", BigDecimal(110), 1000L), 1000L)
    engine.currentExposure.netExposure shouldBe BigDecimal(133000)
    engine.currentExposure.grossExposure shouldBe BigDecimal(177000)
    engine.onTick(PriceTick("STAB", BigDecimal(60), 2000L), 2000L)
    engine.currentExposure.netExposure shouldBe BigDecimal(131000)
    engine.currentExposure.grossExposure shouldBe BigDecimal(179000)
    engine.audit.map(_.deltaNet) shouldBe
      List(BigDecimal(-10000), BigDecimal(-2000))
  }

  it should "reconcile incremental state with full recomputation" in {
    val queue = new InMemoryAlertQueue
    val engine = new StreamingRiskEngine(
      Fixtures.bookA1, Fixtures.prices, Fixtures.infos, queue)
    val ticks = List(
      PriceTick("ACME", BigDecimal(115), 1L),
      PriceTick("GLOB", BigDecimal(95), 2L),
      PriceTick("ACME", BigDecimal(118), 3L),
      PriceTick("STAB", BigDecimal(50), 4L)
    )
    ticks.foreach(t => engine.onTick(t, t.timestampMillis))
    engine.currentExposure shouldBe engine.reconcile()
    engine.ticksProcessed shouldBe 4
  }

  it should "alert once for a persistent breach, not once per tick" in {
    val queue = new InMemoryAlertQueue
    val engine = new StreamingRiskEngine(
      Fixtures.bookA1, Fixtures.prices, Fixtures.infos, queue)
    // The fixture book already breaches gross soft at close prices,
    // so the first tick alerts; subsequent ticks do not re-alert.
    engine.onTick(PriceTick("ACME", BigDecimal(121), 1L), 1L)
    val afterFirst = queue.size
    afterFirst should be > 0
    engine.onTick(PriceTick("ACME", BigDecimal(122), 2L), 2L)
    engine.onTick(PriceTick("GLOB", BigDecimal(91), 3L), 3L)
    queue.size shouldBe afterFirst
  }
}
