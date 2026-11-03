package com.backoffice.capture.validation

import scala.collection.mutable

/**
 * Detects gaps in FIX MsgSeqNum per sender.
 *
 * A gap means an execution report was lost, delayed, or never sent.
 * Gaps are recorded, not thrown: a gap is an operational event that
 * needs investigation, not a reason to stop processing the messages
 * that did arrive. Stopping on a gap would turn one missing trade
 * into a full pipeline stall.
 */
class SequenceGapDetector {
  private val lastSeen = mutable.Map.empty[String, Long]
  private val gaps = mutable.ListBuffer.empty[SequenceGap]

  case class SequenceGap(
    sender: String,
    expected: Long,
    received: Long,
    detectedAtMs: Long
  )

  /**
   * Record an observed sequence number. Returns the gap, if this
   * arrival skipped ahead of the expected number.
   */
  def observe(sender: String, seqNum: Long): Option[SequenceGap] = {
    val gap = lastSeen.get(sender) match {
      case None => None // first message from this sender: no baseline yet
      case Some(last) if seqNum == last + 1 => None
      case Some(last) if seqNum <= last =>
        // Duplicate or out-of-order redelivery: not a gap, but worth
        // noting in logs. The duplicate rule handles the trade itself.
        None
      case Some(last) =>
        Some(SequenceGap(sender, last + 1, seqNum, System.currentTimeMillis()))
    }
    gap.foreach(g => gaps += g)
    lastSeen(sender) = math.max(lastSeen.getOrElse(sender, 0L), seqNum)
    gap
  }

  def openGaps: List[SequenceGap] = gaps.toList

  def gapCount: Int = gaps.size
}
