package recon

import java.time.LocalDate

/** A known custodian fee that can explain a small cash difference. */
case class KnownFee(
  custodian: String,
  currency: String,
  amount: BigDecimal,
  description: String
)

/** A break waiting for a human, with a suggested next action. */
case class ManualQueueItem(
  break: Break,
  suggestedAction: String,
  assignedTeam: String
)

/** Applies auto-resolution rules; everything else goes to the manual queue. */
class ResolutionEngine(knownFees: Seq[KnownFee], asOfDate: LocalDate) {

  def resolve(breaks: Seq[Break]): (Seq[Resolution], Seq[ManualQueueItem]) = {
    val resolutions = scala.collection.mutable.ListBuffer.empty[Resolution]
    val queue = scala.collection.mutable.ListBuffer.empty[ManualQueueItem]

    breaks.foreach { b =>
      autoResolve(b) match {
        case Some(resolution) => resolutions += resolution
        case None => queue += ManualQueueItem(b, suggestAction(b), teamFor(b))
      }
    }
    (resolutions.toSeq, queue.toSeq)
  }

  private def autoResolve(b: Break): Option[Resolution] = b.breakType match {
    case DateMismatch =>
      // Cash and quantity already agree (fuzzy match guarantees this within
      // tolerance, and date breaks carry the matched pair's values).
      Some(Resolution(b.breakId, AutoResolved,
        "Late settlement acknowledged: securities and cash agree, " +
          "settlement date difference recorded for operations reporting",
        Some(asOfDate)))
    case AmountMismatch =>
      // Auto-resolve only if the difference equals a known fee exactly.
      val diff = for {
        ours <- b.ourCash
        theirs <- b.theirCash
      } yield (ours - theirs).abs
      val fee = diff.flatMap(d =>
        knownFees.find(f =>
          f.custodian == b.custodian && f.currency == b.currency && f.amount == d))
      fee.map { f =>
        Resolution(b.breakId, AutoResolved,
          s"Cash difference explained by known fee: ${f.description} (${f.amount} ${b.currency})",
          Some(asOfDate))
      }
    case WrongAccount =>
      // Same custodian, and the break record already identifies the correct
      // account via the trade. The resolution is a book transfer instruction,
      // not a write-off: the engine records the correction, it does not move
      // money by itself.
      Some(Resolution(b.breakId, AutoResolved,
        "Wrong-account settlement identified: book transfer to the correct " +
          "account to be instructed by operations (recorded, not executed)",
        Some(asOfDate)))
    case MissingTrade | UnexpectedEntry =>
      None // never auto-resolved
  }

  private def suggestAction(b: Break): String = b.breakType match {
    case MissingTrade =>
      "Contact custodian: confirm whether the instruction was received; " +
        "if the trade failed, arrange buy-in or re-instruction"
    case UnexpectedEntry =>
      "Investigate source: confirm whether this is a duplicate settlement, " +
        "a trade booked under a different reference, or an erroneous entry"
    case AmountMismatch =>
      "Compare fee and tax schedules with the custodian; obtain a " +
        "breakdown of the cash difference before writing off"
    case DateMismatch =>
      "Confirm late-settlement reason with custodian; check for " +
        "compensation claims if the delay caused a loss"
    case WrongAccount =>
      "Instruct a book transfer from the wrong account to the correct " +
        "account and confirm the transfer settles"
  }

  private def teamFor(b: Break): String = b.breakType match {
    case MissingTrade | UnexpectedEntry => "Settlements Investigations"
    case AmountMismatch => "Finance / Fees"
    case DateMismatch => "Settlements Operations"
    case WrongAccount => "Custody Operations"
  }
}
