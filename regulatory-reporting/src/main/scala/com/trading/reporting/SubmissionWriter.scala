package com.trading.reporting

import java.time.Instant

/** Control totals for one submission file. */
case class ControlTotals(
  recordCount: Long,
  totalQuantity: Long,
  totalNotional: BigDecimal
)

object ControlTotals {
  def fromCat(reports: List[CatReport]): ControlTotals = {
    val notional = reports.flatMap(r => r.price.map(p => p * r.quantity)).sum
    ControlTotals(reports.size, reports.map(_.quantity).sum, notional)
  }

  def fromMifir(reports: List[MifirReport]): ControlTotals = {
    val notional = reports.map(r => r.price * r.quantity).sum
    ControlTotals(reports.size, reports.map(_.quantity).sum, notional)
  }
}

/** A complete submission file: header, body lines, trailer. */
case class SubmissionFile(
  fileId: String,
  regime: String,
  reportingDate: java.time.LocalDate,
  header: String,
  bodyLines: List[String],
  trailer: String,
  totals: ControlTotals
)

object SubmissionWriter {

  def writeCat(
    fileId: String,
    reports: List[CatReport],
    reportingDate: java.time.LocalDate
  ): SubmissionFile = {
    val header = List(
      "HDR", "CAT", reportingDate.toString,
      reports.headOption.map(_.firmId).getOrElse(""),
      Instant.now().toString
    ).mkString("|")
    val body = reports.map { r =>
      List(
        r.firmId, r.orderId, r.eventType, r.eventTimestamp.toString,
        r.symbol, r.side, r.quantity.toString,
        r.price.map(_.toString).getOrElse(""),
        r.capacity, r.linkageKey, r.isLate.toString, r.version.toString
      ).mkString("|")
    }
    val totals = ControlTotals.fromCat(reports)
    val trailer = List(
      "TRL", totals.recordCount.toString,
      totals.totalQuantity.toString, totals.totalNotional.toString
    ).mkString("|")
    SubmissionFile(fileId, "CAT", reportingDate, header, body, trailer, totals)
  }

  def writeMifir(
    fileId: String,
    reports: List[MifirReport],
    reportingDate: java.time.LocalDate
  ): SubmissionFile = {
    val header = List(
      "HDR", "MIFIR", reportingDate.toString,
      Instant.now().toString
    ).mkString("|")
    val body = reports.map { r =>
      List(
        r.transactionReferenceNumber, r.tradingVenue,
        r.instrumentIdentification, r.price.toString, r.quantity.toString,
        r.transactionDateTime.toString, r.buyerIdentification,
        r.sellerIdentification, r.investmentDecisionMaker, r.executingPerson,
        r.countryOfBranch, r.waiverIndicator, r.shortSellingIndicator,
        r.commodityDerivativeIndicator, r.version.toString
      ).mkString("|")
    }
    val totals = ControlTotals.fromMifir(reports)
    val trailer = List(
      "TRL", totals.recordCount.toString,
      totals.totalQuantity.toString, totals.totalNotional.toString
    ).mkString("|")
    SubmissionFile(fileId, "MIFIR", reportingDate, header, body, trailer, totals)
  }

  /** Render a submission file as text, for writing to disk or transmitting. */
  def render(file: SubmissionFile): String =
    (file.header :: file.bodyLines ::: List(file.trailer)).mkString("\n")
}
