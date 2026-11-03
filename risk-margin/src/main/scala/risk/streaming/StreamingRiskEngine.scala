package risk.streaming

import risk.domain._
import risk.exposure.{Exposure, ExposureCalculator}
import risk.limits.{AlertQueue, Breach, LimitChecker}

/** One audit record per applied tick. The audit log is what makes an
  * incremental engine trustworthy: any state can be replayed and
  * reconciled against a full recomputation.
  */
final case class RefreshAudit(
  symbol: String,
  oldPrice: BigDecimal,
  newPrice: BigDecimal,
  deltaNet: BigDecimal,
  timestampMillis: Long
)

/** Mutable streaming state, deliberately kept in one class so that
  * the update path is reviewable in a single place. In production
  * this state lives behind a stream processor with exactly-once
  * semantics; the tutorial version keeps the same update logic with
  * plain mutable state so the logic, not the framework, is what you
  * learn.
  */
class StreamingRiskEngine(
  positions: List[Position],
  initialPrices: Map[String, BigDecimal],
  infos: Map[String, SymbolInfo],
  queue: AlertQueue
) {
  private var prices: Map[String, BigDecimal] = initialPrices
  private var exposure: Exposure =
    ExposureCalculator.compute(positions, prices, infos)._1
  private var activeBreachKeys: Set[String] = Set.empty
  private var auditLog: List[RefreshAudit] = Nil
  private var tickCount: Int = 0

  def currentExposure: Exposure = exposure
  def currentPrices: Map[String, BigDecimal] = prices
  def audit: List[RefreshAudit] = auditLog.reverse
  def ticksProcessed: Int = tickCount

  /** Apply one price tick: update the price, adjust exposure by the
    * delta for that symbol only, re-evaluate limits, and publish any
    * genuinely new breaches. Returns breaches found on this tick.
    */
  def onTick(tick: PriceTick, nowMillis: Long): List[Breach] = {
    val oldPrice = prices.getOrElse(tick.symbol, BigDecimal(0))
    prices = prices.updated(tick.symbol, tick.price)
    tickCount += 1

    // Incremental update: only positions in the ticked symbol move.
    // Delta to net exposure = quantity × (new − old) summed over
    // positions in that symbol. Full exposure is then recomputed
    // from the (small) position list — cheap here, and it keeps the
    // long/short buckets exactly right, including sign flips when a
    // price crosses zero (not possible for equities, but the code
    // does not rely on that).
    val (fresh, missing) =
      ExposureCalculator.compute(positions, prices, infos)
    require(missing.isEmpty, s"missing prices after tick: $missing")
    val deltaNet = fresh.netExposure - exposure.netExposure
    auditLog = RefreshAudit(
      tick.symbol, oldPrice, tick.price, deltaNet, nowMillis) :: auditLog
    exposure = fresh

    // Limits re-evaluate on every exposure refresh. VaR is intentionally
    // NOT recomputed here: it refreshes on schedule (see 8.10.4).
    // The limit check below uses the last computed VaR, passed in by
    // the caller through `lastVarDollars` in production; the tutorial
    // test path evaluates exposure limits only, which is the part
    // that must be tick-accurate.
    val deskIds = positions.map(_.deskId).distinct
    val breaches = deskIds.flatMap { desk =>
      val deskPositions = positions.filter(_.deskId == desk)
      val (deskExp, _) =
        ExposureCalculator.compute(deskPositions, prices, infos)
      // Exposure and concentration limits only on the tick path.
      LimitChecker.deskLimits(desk)
        .filter(l => l.name != "var-99-1day")
        .flatMap { limit =>
          val value = LimitChecker.observedValues(
            deskExp,
            risk.varx.VaRResult(0.99, 1, 0.0, BigDecimal(0),
              0.0, java.time.LocalDate.now(), 0)
          ).getOrElse(limit.name, 0.0)
          if (value >= limit.hardThreshold) Some(risk.limits.Breach(
            limit.name, desk, risk.limits.Severity.Critical, value,
            limit.hardThreshold,
            s"HARD breach (intraday): ${limit.name} at $value"))
          else if (value >= limit.softThreshold) Some(risk.limits.Breach(
            limit.name, desk, risk.limits.Severity.Warning, value,
            limit.softThreshold,
            s"SOFT breach (intraday): ${limit.name} at $value"))
          else None
        }
    }
    activeBreachKeys = LimitChecker.publishNewBreaches(
      breaches, queue, activeBreachKeys, nowMillis)
    breaches
  }

  /** Full recomputation, for reconciliation: the incremental state
    * must equal this after any sequence of ticks. Run it in tests
    * and in production health checks, not on the tick path.
    */
  def reconcile(): Exposure =
    ExposureCalculator.compute(positions, prices, infos)._1
}
