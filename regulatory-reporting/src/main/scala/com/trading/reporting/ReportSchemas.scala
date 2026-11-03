package com.trading.reporting

import java.time.{Instant, LocalDate}

/** A CAT transaction report record (simplified CAT specification). */
case class CatReport(
  firmId: String,
  orderId: String,
  eventType: String,
  eventTimestamp: Instant,
  symbol: String,
  side: String,
  quantity: Long,
  price: Option[BigDecimal],
  capacity: String,
  reportingDate: LocalDate,
  linkageKey: String,
  isLate: Boolean,
  version: Int,
  originalReportId: Option[String]
)

/** A MiFIR transaction report record (simplified Article 26 schema). */
case class MifirReport(
  transactionReferenceNumber: String,
  tradingVenue: String,
  instrumentIdentification: String,
  price: BigDecimal,
  quantity: Long,
  transactionDateTime: Instant,
  buyerIdentification: String,
  sellerIdentification: String,
  investmentDecisionMaker: String,
  executingPerson: String,
  countryOfBranch: String,
  waiverIndicator: String,
  shortSellingIndicator: String,
  commodityDerivativeIndicator: String,
  reportingDate: LocalDate,
  version: Int,
  originalReportId: Option[String]
)
