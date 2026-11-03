package recon

import java.time.LocalDate

/** Turns match results into classified breaks. */
class BreakClassifier(asOfDate: LocalDate) {

  private var counter = 0
  private def nextId(): String = {
    counter += 1
    f"BRK-$counter%04d"
  }

  def classify(
    results: Seq[MatchResult],
    allInstructions: Seq[SettlementInstruction]
  ): Seq[Break] = {
    results.flatMap { result =>
      result.kind match {
        case ExactMatch => Nil
        case FuzzyMatch => classifyFuzzy(result)
        case UnmatchedInstruction => classifyMissing(result)
        case UnmatchedStatement => classifyUnexpected(result, allInstructions)
      }
    }
  }

  private def classifyFuzzy(result: MatchResult): Seq[Break] = {
    val inst = result.instruction.get
    val stmt = result.statement.get
    val breaks = scala.collection.mutable.ListBuffer.empty[Break]

    result.cashDifference.foreach { diff =>
      if (diff != 0) {
        breaks += Break(
          breakId = nextId(),
          tradeId = Some(inst.tradeId),
          statementId = Some(stmt.statementId),
          breakType = AmountMismatch,
          severity = Low,
          ourQuantity = Some(StatementConventions.statementQuantity(inst)),
          theirQuantity = Some(stmt.quantity),
          ourCash = Some(StatementConventions.statementCash(inst)),
          theirCash = Some(stmt.cashAmount),
          ourDate = Some(inst.settlementDate),
          theirDate = Some(stmt.valueDate),
          account = inst.account,
          custodian = inst.custodian,
          currency = inst.currency,
          detectedDate = asOfDate,
          description = s"Cash differs by $diff ${inst.currency} " +
            s"(ours ${StatementConventions.statementCash(inst)}, theirs ${stmt.cashAmount})"
        )
      }
    }

    result.dateDifferenceDays.foreach { days =>
      if (days != 0) {
        breaks += Break(
          breakId = nextId(),
          tradeId = Some(inst.tradeId),
          statementId = Some(stmt.statementId),
          breakType = DateMismatch,
          severity = Medium,
          ourQuantity = Some(StatementConventions.statementQuantity(inst)),
          theirQuantity = Some(stmt.quantity),
          ourCash = Some(StatementConventions.statementCash(inst)),
          theirCash = Some(stmt.cashAmount),
          ourDate = Some(inst.settlementDate),
          theirDate = Some(stmt.valueDate),
          account = inst.account,
          custodian = inst.custodian,
          currency = inst.currency,
          detectedDate = asOfDate,
          description = s"Settled $days business days from expected date " +
            s"(expected ${inst.settlementDate}, actual ${stmt.valueDate})"
        )
      }
    }
    breaks.toSeq
  }

  private def classifyMissing(result: MatchResult): Seq[Break] = {
    val inst = result.instruction.get
    val overdue = inst.settlementDate.isBefore(asOfDate)
    Seq(Break(
      breakId = nextId(),
      tradeId = Some(inst.tradeId),
      statementId = None,
      breakType = MissingTrade,
      severity = if (overdue) High else Medium,
      ourQuantity = Some(StatementConventions.statementQuantity(inst)),
      theirQuantity = None,
      ourCash = Some(StatementConventions.statementCash(inst)),
      theirCash = None,
      ourDate = Some(inst.settlementDate),
      theirDate = None,
      account = inst.account,
      custodian = inst.custodian,
      currency = inst.currency,
      detectedDate = asOfDate,
      description = if (overdue)
        s"Trade ${inst.tradeId} expected to settle ${inst.settlementDate} " +
          s"but no custodian entry found (overdue)"
      else
        s"Trade ${inst.tradeId} expected to settle ${inst.settlementDate} " +
          s"but no custodian entry found yet (settlement date is today)"
    ))
  }

  /** An unmatched statement line is either a wrong-account settlement of a
    * known trade, or a genuinely unexpected entry. The account-ignored
    * re-check is what separates the two. */
  private def classifyUnexpected(
    result: MatchResult,
    allInstructions: Seq[SettlementInstruction]
  ): Seq[Break] = {
    val stmt = result.statement.get
    val wrongAccountCandidate = allInstructions.find { inst =>
      inst.instrument == stmt.instrument &&
      StatementConventions.statementQuantity(inst) == stmt.quantity &&
      StatementConventions.statementCash(inst) == stmt.cashAmount &&
      inst.currency == stmt.currency &&
      inst.settlementDate == stmt.valueDate &&
      inst.custodian == stmt.custodian &&
      inst.account != stmt.account
    }
    wrongAccountCandidate match {
      case Some(inst) =>
        Seq(Break(
          breakId = nextId(),
          tradeId = Some(inst.tradeId),
          statementId = Some(stmt.statementId),
          breakType = WrongAccount,
          severity = Medium,
          ourQuantity = Some(StatementConventions.statementQuantity(inst)),
          theirQuantity = Some(stmt.quantity),
          ourCash = Some(StatementConventions.statementCash(inst)),
          theirCash = Some(stmt.cashAmount),
          ourDate = Some(inst.settlementDate),
          theirDate = Some(stmt.valueDate),
          account = stmt.account,
          custodian = inst.custodian,
          currency = inst.currency,
          detectedDate = asOfDate,
          description = s"Trade ${inst.tradeId} settled into account ${stmt.account} " +
            s"instead of expected account ${inst.account}"
        ))
      case None =>
        Seq(Break(
          breakId = nextId(),
          tradeId = None,
          statementId = Some(stmt.statementId),
          breakType = UnexpectedEntry,
          severity = Medium,
          ourQuantity = None,
          theirQuantity = Some(stmt.quantity),
          ourCash = None,
          theirCash = Some(stmt.cashAmount),
          ourDate = None,
          theirDate = Some(stmt.valueDate),
          account = stmt.account,
          custodian = stmt.custodian,
          currency = stmt.currency,
          detectedDate = asOfDate,
          description = s"Custodian entry ${stmt.statementId} for ${stmt.instrument} " +
            s"has no corresponding instruction in our books"
        ))
    }
  }
}
