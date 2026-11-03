package com.backoffice.capture.validation

import com.backoffice.capture.model._
import java.time.LocalDate

object Validator {

  /**
   * Validate one execution. Returns Right(Trade) if every rule passes,
   * Left(Rejection) with the first failing rule otherwise.
   */
  def validate(raw: RawExecution, ctx: ValidationContext): Either[Rejection, Trade] = {
    Rules.all.collectFirst {
      case (_, rule) => rule(raw, ctx)
    }.flatten match {
      case Some(rejection) => Left(rejection)
      case None =>
        val info = ctx.securities(raw.symbol)
        Right(Trade(
          execId = raw.execId,
          orderId = raw.orderId,
          symbol = raw.symbol,
          side = raw.side,
          execType = raw.execType,
          quantity = raw.lastQty,
          price = raw.lastPx,
          tradeDate = raw.transactTime.atZone(
            java.time.ZoneId.of("America/New_York")).toLocalDate,
          counterparty = raw.senderCompId
        ))
    }
  }

  /**
   * Diagnostic mode: run every rule and return all failures.
   * Used by the replay tool and by operations dashboards to show
   * everything wrong with a trade at once, rather than one rule at a time.
   */
  def validateAll(raw: RawExecution, ctx: ValidationContext): List[Rejection] =
    Rules.all.flatMap { case (_, rule) => rule(raw, ctx) }
}
