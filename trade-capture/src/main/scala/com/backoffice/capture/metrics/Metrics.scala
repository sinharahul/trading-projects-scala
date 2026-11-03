package com.backoffice.capture.metrics

import org.slf4j.LoggerFactory
import scala.collection.mutable

/**
 * Rolling-window metrics with a reject-rate alert.
 *
 * The alert rule: if the reject rate over the last N minutes exceeds
 * the configured threshold, log an ALERT and (in production) page
 * operations. The alert fires at most once per window, so a sustained
 * problem pages once, not once per trade.
 */
class Metrics(alertThresholdPct: Double, windowMinutes: Int) {
  private val log = LoggerFactory.getLogger(getClass)

  private case class Event(timestampMs: Long, accepted: Boolean, reason: Option[String])

  private val events = mutable.ListBuffer.empty[Event]
  private var lastAlertMs: Long = 0L
  private var totalReceived: Long = 0L
  private var totalAccepted: Long = 0L
  private val rejectsByReason = mutable.Map.empty[String, Long].withDefaultValue(0L)

  def recordReceived(): Unit = {
    totalReceived += 1
    prune()
  }

  def recordAccepted(): Unit = {
    totalAccepted += 1
    events += Event(System.currentTimeMillis(), accepted = true, None)
    prune()
  }

  def recordRejected(reason: String): Unit = {
    rejectsByReason(reason) += 1
    events += Event(System.currentTimeMillis(), accepted = false, Some(reason))
    prune()
    checkAlert()
  }

  /** Reject rate over the rolling window, as a percentage. */
  def rejectRatePct: Double = {
    prune()
    if (events.isEmpty) 0.0
    else events.count(!_.accepted).toDouble / events.size * 100.0
  }

  def snapshot: String = {
    val byReason = rejectsByReason.toSeq.sortBy(-_._2)
      .map { case (code, count) => s"$code=$count" }.mkString(", ")
    s"received=$totalReceived accepted=$totalAccepted " +
    s"windowRejectRate=${f"$rejectRatePct%.1f"}%% rejects[$byReason]"
  }

  private def prune(): Unit = {
    val cutoff = System.currentTimeMillis() - windowMinutes * 60 * 1000L
    events.filterInPlace(_.timestampMs >= cutoff)
  }

  private def checkAlert(): Unit = {
    val now = System.currentTimeMillis()
    if (rejectRatePct > alertThresholdPct && now - lastAlertMs > windowMinutes * 60 * 1000L) {
      lastAlertMs = now
      log.error("ALERT: reject rate {}%% exceeds threshold {}%% over last {} minutes. {}",
        f"$rejectRatePct%.1f", alertThresholdPct, windowMinutes, snapshot)
    }
  }
}
