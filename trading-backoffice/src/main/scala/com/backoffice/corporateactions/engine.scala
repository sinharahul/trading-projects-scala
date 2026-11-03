package com.backoffice.corporateactions

import scala.collection.mutable

/** Durable record of which actions have been applied to which
  * positions. In production this is a database table with a unique
  * constraint on (actionId, version, accountId, symbol); the
  * in-memory version here has identical check-then-apply semantics. */
class AppliedActionRegistry {
  private val applied = mutable.Set.empty[(String, Int, String, String)]

  /** True if this exact action version has already been applied to
    * this account and symbol. */
  def alreadyApplied(
    action: CorporateAction,
    accountId: String,
    symbol: String
  ): Boolean =
    applied.contains((action.actionId, action.version, accountId, symbol))

  /** Record an application. Returns false if it was already recorded,
    * which the caller treats as a duplicate, not an error. */
  def markApplied(
    action: CorporateAction,
    accountId: String,
    symbol: String
  ): Boolean =
    applied.add((action.actionId, action.version, accountId, symbol))

  def appliedCount: Int = applied.size
}

/** The outcome of attempting to apply an action to one position. */
sealed trait ApplyResult
object ApplyResult {
  case class Applied(record: AuditRecord) extends ApplyResult
  case class AlreadyApplied(actionId: String, accountId: String) extends ApplyResult
  case class Failed(actionId: String, reason: String) extends ApplyResult
}

object IdempotentEngine {

  /** Apply a split to one position, exactly once.
    *
    * If the action version has already been applied to this account
    * and symbol, the position is returned unchanged and the result
    * is AlreadyApplied — not an error, and not a second adjustment.
    */
  def applySplit(
    position: Position,
    action: CorporateAction,
    treatment: FractionalTreatment,
    registry: AppliedActionRegistry,
    appliedBy: String
  ): (Position, ApplyResult) = {
    if (registry.alreadyApplied(action, position.accountId, position.symbol)) {
      (position, ApplyResult.AlreadyApplied(action.actionId, position.accountId))
    } else {
      val result = SplitAdjuster.adjust(position, action, treatment)
      val audit = AuditWriter.forSplitAdjustment(
        auditId = s"AUD-${action.actionId}-${position.accountId}",
        action = action,
        result = result,
        appliedBy = appliedBy
      )
      registry.markApplied(action, position.accountId, position.symbol)
      (result.after, ApplyResult.Applied(audit))
    }
  }

  /** Apply a split to a whole book, returning the adjusted book and
    * the audit records for the positions that actually changed. */
  def applySplitToBook(
    positions: List[Position],
    action: CorporateAction,
    treatment: FractionalTreatment,
    registry: AppliedActionRegistry,
    appliedBy: String
  ): (List[Position], List[AuditRecord]) = {
    val outcomes = positions
      .filter(_.symbol == action.symbol)
      .map(p => applySplit(p, action, treatment, registry, appliedBy))
    val untouched = positions.filter(_.symbol != action.symbol)
    val adjusted = outcomes.map(_._1)
    val audits = outcomes.collect { case (_, ApplyResult.Applied(r)) => r }
    (untouched ++ adjusted, audits)
  }
}

object CorrectionEngine {

  /** Reverse a previously applied split adjustment, restoring the
    * before values from the audit record. The reversal itself is
    * audited by the caller. */
  def reverseSplit(record: AuditRecord, current: Position): Position =
    current.copy(
      quantity = record.quantityBefore,
      costBasisPerShare = record.costBasisBefore
    )

  /** Apply a corrected action version after reversing the original.
    * The registry key includes the version, so the corrected version
    * is not mistaken for a duplicate. */
  def applyCorrection(
    position: Position,
    originalAudit: AuditRecord,
    correctedAction: CorporateAction,
    treatment: FractionalTreatment,
    registry: AppliedActionRegistry,
    appliedBy: String
  ): (Position, AuditRecord) = {
    val restored = reverseSplit(originalAudit, position)
    val (adjusted, result) = IdempotentEngine.applySplit(
      restored, correctedAction, treatment, registry, appliedBy)
    result match {
      case ApplyResult.Applied(record) => (adjusted, record)
      case other =>
        throw new IllegalStateException(
          s"correction failed: unexpected result $other")
    }
  }
}

/** A batch run: a sequence of actions applied to a book in
  * effective-date order, with idempotency across the run and a
  * completeness report at the end. */
object BatchRunner {

  case class BatchReport(
    actionsApplied: Int,
    actionsSkippedAsDuplicates: Int,
    auditRecords: List[AuditRecord],
    errors: List[String]
  )

  /** Apply a sequence of split actions to a book. Other action types
    * follow the same pattern with their own engines; the runner's
    * job is ordering, idempotency, and reporting, not adjustment
    * mathematics. */
  def runSplits(
    book: List[Position],
    actions: List[CorporateAction],
    treatment: FractionalTreatment,
    registry: AppliedActionRegistry,
    appliedBy: String
  ): (List[Position], BatchReport) = {
    val ordered = actions.sortBy(_.effectiveDate)
    var current = book
    var audits = List.empty[AuditRecord]
    var applied = 0
    var skipped = 0
    val errors = List.newBuilder[String]

    ordered.foreach { action =>
      CorporateActionValidator.validate(action) match {
        case Left(errs) =>
          errors += s"${action.actionId}: ${errs.map(_.message).mkString("; ")}"
        case Right(valid) =>
          val (newBook, newAudits) = IdempotentEngine.applySplitToBook(
            current, valid, treatment, registry, appliedBy)
          val inScope = current.count(_.symbol == valid.symbol)
          if (newAudits.size < inScope) {
            // Some positions were already applied: count them as
            // skipped, not as errors. The registry is the source of
            // truth for what was skipped and why.
            skipped += inScope - newAudits.size
          }
          applied += 1
          audits = audits ++ newAudits
          current = newBook
      }
    }

    (current, BatchReport(applied, skipped, audits, errors.result()))
  }
}
