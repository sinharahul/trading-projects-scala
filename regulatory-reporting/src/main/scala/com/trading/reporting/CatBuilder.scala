package com.trading.reporting

import java.time.{Instant, LocalDate, ZoneId}

object CatBuilder {

  private val ReportingZone = ZoneId.of("America/New_York")

  /** The single place linkage keys are constructed. Never duplicate this logic. */
  def linkageKey(firmId: String, orderId: String, reportingDate: LocalDate): String =
    s"$firmId|$orderId|$reportingDate"

  /** Event date in the reporting timezone, for deadline computation. */
  def eventDate(timestamp: Instant): LocalDate =
    timestamp.atZone(ReportingZone).toLocalDate

  /**
   * A report is late if the reporting date is more than one calendar day
   * after the event date. Production systems use a business calendar;
   * this simplified rule is documented as an extension point.
   */
  def isLate(eventTimestamp: Instant, reportingDate: LocalDate): Boolean =
    reportingDate.isAfter(eventDate(eventTimestamp).plusDays(1))

  private def sideCode(side: Side): String = side match {
    case Side.Buy      => "B"
    case Side.Sell     => "S"
    case Side.SellShort => "SS"
  }

  private def capacityCode(capacity: Capacity): String = capacity match {
    case Capacity.Agency            => "A"
    case Capacity.Principal         => "P"
    case Capacity.RisklessPrincipal => "R"
  }

  /**
   * Build one CAT record per reportable event for each enriched trade.
   * Only instruments flagged as CAT-eligible produce records. Options
   * produce records with the same structure; the instrument type is
   * carried in the symbol and reference data, and option-specific
   * fields are an extension covered in the pitfalls section.
   */
  def build(
    enriched: List[EnrichedTrade],
    reportIdPrefix: String
  ): List[CatReport] = {
    enriched
      .filter(_.instrument.isEligibleForCat)
      .flatMap { et =>
        val trade = et.trade
        et.orderEvents.map { event =>
          CatReport(
            firmId = trade.firmId,
            orderId = trade.orderId,
            eventType = event.eventType.toString,
            eventTimestamp = event.eventTimestamp,
            symbol = event.symbol,
            side = sideCode(event.side),
            quantity = event.quantity,
            price = event.price,
            capacity = capacityCode(trade.capacity),
            reportingDate = et.reportingDate,
            linkageKey = linkageKey(trade.firmId, trade.orderId, et.reportingDate),
            isLate = isLate(event.eventTimestamp, et.reportingDate),
            version = 1,
            originalReportId = None
          )
        }
      }
  }
}
