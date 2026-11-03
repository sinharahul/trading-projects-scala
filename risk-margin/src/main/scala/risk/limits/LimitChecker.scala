package risk.limits

import risk.domain._
import risk.exposure.Exposure
import risk.varx.VaRResult

/** Severity: Warning asks for explanation; Critical restricts activity. */
sealed trait Severity
object Severity {
  case object Warning extends Severity
  case object Critical extends Severity
}

/** One limit definition: a name, a scope, and soft/hard thresholds. */
final case class Limit(
  name: String,
  scope: String,           // desk id, account id, or "FIRM"
  softThreshold: Double,
  hardThreshold: Double
)

/** A breach event: what broke, by how much, and how serious it is. */
final case class Breach(
  limitName: String,
  scope: String,
  severity: Severity,
  observed: Double,
  threshold: Double,
  message: String
)

/** An alert ready for delivery, with a stable key for deduplication. */
final case class Alert(
  key: String,             // e.g. "DESK-1:gross-exposure:soft"
  breach: Breach,
  timestampMillis: Long
)

/** Alert delivery abstraction. Production implementations publish to
  * Kafka or a durable queue; this tutorial uses an in-memory one
  * with the identical interface, so the swap is mechanical.
  */
trait AlertQueue {
  def publish(alert: Alert): Unit
  def drain(): List[Alert]
  def size: Int
}

class InMemoryAlertQueue extends AlertQueue {
  private val buffer = scala.collection.mutable.ListBuffer.empty[Alert]
  def publish(alert: Alert): Unit = buffer += alert
  def drain(): List[Alert] = { val out = buffer.toList; buffer.clear(); out }
  def size: Int = buffer.size
}

object LimitChecker {

  /** The standard limit set for a desk. Thresholds are policy, not
    * physics: they belong in configuration in production, and they are
    * written here explicitly so the tests pin them down.
    */
  def deskLimits(deskId: String): List[Limit] = List(
    Limit("gross-exposure", deskId, softThreshold = 150000, hardThreshold = 250000),
    Limit("net-exposure", deskId, softThreshold = 120000, hardThreshold = 200000),
    Limit("concentration", deskId, softThreshold = 0.50, hardThreshold = 0.70),
    Limit("var-99-1day", deskId, softThreshold = 5000, hardThreshold = 10000)
  )

  /** Observed values for each limit family, in limit order. */
  def observedValues(
    exp: Exposure,
    var99: VaRResult
  ): Map[String, Double] = {
    val concentration =
      if (exp.grossExposure == 0) 0.0
      else exp.marketValueBySymbol.values.map(_.abs).max.toDouble /
        exp.grossExposure.toDouble
    Map(
      "gross-exposure" -> exp.grossExposure.toDouble,
      "net-exposure" -> exp.netExposure.toDouble,
      "concentration" -> concentration,
      "var-99-1day" -> var99.varDollars.toDouble
    )
  }

  /** Evaluate all limits. Returns breaches; an empty list means clean. */
  def evaluate(
    deskId: String,
    exp: Exposure,
    var99: VaRResult
  ): List[Breach] = {
    val observed = observedValues(exp, var99)
    deskLimits(deskId).flatMap { limit =>
      val value = observed(limit.name)
      if (value >= limit.hardThreshold) Some(Breach(
        limit.name, deskId, Severity.Critical, value, limit.hardThreshold,
        s"HARD breach: ${limit.name} at $value exceeds hard limit " +
          s"${limit.hardThreshold} for $deskId"))
      else if (value >= limit.softThreshold) Some(Breach(
        limit.name, deskId, Severity.Warning, value, limit.softThreshold,
        s"SOFT breach: ${limit.name} at $value exceeds soft limit " +
          s"${limit.softThreshold} for $deskId"))
      else None
    }
  }

  /** Publish breaches as alerts, suppressing duplicates for a breach
    * key that is already active. `activeKeys` is the set of keys
    * alerted and not yet cleared; callers update it from the return
    * value. Without this, a streaming refresh (Step 7) would emit an
    * alert on every tick for the same breach, and humans would stop
    * reading alerts within a week — the most common operational
    * failure of limit systems.
    */
  def publishNewBreaches(
    breaches: List[Breach],
    queue: AlertQueue,
    activeKeys: Set[String],
    nowMillis: Long
  ): Set[String] = {
    val newKeys = breaches.map { b =>
      val level = b.severity match {
        case Severity.Warning => "soft"
        case Severity.Critical => "hard"
      }
      s"${b.scope}:${b.limitName}:$level"
    }
    // Escalation counts as new even if a soft alert is active: a desk
    // that drifts from soft to hard must re-alert, loudly.
    val toPublish = breaches.zip(newKeys).filter { case (_, key) =>
      !activeKeys.contains(key)
    }
    toPublish.foreach { case (b, key) =>
      queue.publish(Alert(key, b, nowMillis))
    }
    // Keys that are no longer breaching are cleared, so a future
    // re-breach alerts again. A breach that clears and returns is
    // a new event, not a duplicate.
    val stillActive = newKeys.toSet
    activeKeys.filter(stillActive.contains) ++ stillActive
  }
}
