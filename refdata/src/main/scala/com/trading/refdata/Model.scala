package com.trading.refdata

import java.time.LocalDate
import java.util.UUID

/** Asset classes the master supports. Keep this closed: an unknown
  * asset class is a data quality failure, not a string to pass through.
  */
sealed trait AssetClass
object AssetClass {
  case object Equity extends AssetClass
  case object FixedIncome extends AssetClass
  case object Fund extends AssetClass
  case object Derivative extends AssetClass
  case object Other extends AssetClass

  def fromString(s: String): Option[AssetClass] = s.trim.toUpperCase match {
    case "EQUITY" | "EQ" | "STOCK" => Some(Equity)
    case "FIXED_INCOME" | "FI" | "BOND" => Some(FixedIncome)
    case "FUND" | "ETF" | "MUTUAL_FUND" => Some(Fund)
    case "DERIVATIVE" | "DERIV" => Some(Derivative)
    case "OTHER" => Some(Other)
    case _ => None
  }
}

/** Identifier types. The `isPrimary` flag marks the types that may
  * serve as matching keys on their own; a ticker alone may not.
  */
sealed trait IdentifierType { def isPrimary: Boolean }
object IdentifierType {
  case object Cusip extends IdentifierType { val isPrimary = true }
  case object Isin extends IdentifierType { val isPrimary = true }
  case object Sedol extends IdentifierType { val isPrimary = true }
  case object Figi extends IdentifierType { val isPrimary = true }
  case object TickerMic extends IdentifierType { val isPrimary = false }
  case object Lei extends IdentifierType { val isPrimary = true } // issuer-level

  def fromString(s: String): Option[IdentifierType] = s.trim.toUpperCase match {
    case "CUSIP" => Some(Cusip)
    case "ISIN" => Some(Isin)
    case "SEDOL" => Some(Sedol)
    case "FIGI" => Some(Figi)
    case "TICKER" | "TICKER_MIC" => Some(TickerMic)
    case "LEI" => Some(Lei)
    case _ => None
  }
}

sealed trait SecurityStatus
object SecurityStatus {
  case object Active extends SecurityStatus
  case object Matured extends SecurityStatus
  case object Delisted extends SecurityStatus
  case object Suspended extends SecurityStatus

  def fromString(s: String): Option[SecurityStatus] = s.trim.toUpperCase match {
    case "ACTIVE" => Some(Active)
    case "MATURED" | "REDEEMED" => Some(Matured)
    case "DELISTED" | "INACTIVE" => Some(Delisted)
    case "SUSPENDED" => Some(Suspended)
    case _ => None
  }
}

/** An issuer: a legal entity. `parentIssuerKey` builds the hierarchy;
  * it is None for ultimate parents.
  */
case class Issuer(
  issuerKey: String,
  lei: Option[String],
  legalName: String,
  domicileCountry: String,
  sector: String,
  parentIssuerKey: Option[String]
)

/** A security: an instrument. `goldenKey` is assigned by the master
  * and never changes. Vendor identifiers live in Identifier rows.
  */
case class Security(
  goldenKey: String,
  issuerKey: String,
  name: String,
  assetClass: AssetClass,
  securityType: String,
  currency: String,
  status: SecurityStatus,
  maturityDate: Option[LocalDate]
)

/** One identifier value attached to a security. `validFrom` and
  * `validTo` record when this identifier value was true for the
  * security, so that identifier reuse can be detected and explained.
  */
case class Identifier(
  goldenKey: String,
  idType: IdentifierType,
  value: String,
  source: String,
  validFrom: LocalDate,
  validTo: Option[LocalDate]
)

/** A listing: a security trading on one venue. */
case class Listing(
  goldenKey: String,
  mic: String,
  ticker: String,
  tradingCurrency: String,
  isPrimaryListing: Boolean,
  status: SecurityStatus
)

/** A raw vendor record, exactly as staged, before any cleaning.
  * Every field is a String here on purpose: parsing and validation
  * happen in later stages, and staging must never reject a row for
  * failing a business rule. A row that fails validation is staged
  * and then quarantined, not dropped at the door.
  */
case class VendorRecord(
  vendor: String,
  vendorSecurityId: String,
  name: String,
  cusip: Option[String],
  isin: Option[String],
  sedol: Option[String],
  figi: Option[String],
  ticker: Option[String],
  mic: Option[String],
  assetClass: String,
  securityType: String,
  currency: String,
  issuerName: String,
  issuerLei: Option[String],
  country: String,
  sector: String,
  status: String,
  asOfDate: LocalDate,
  rawHash: String
)
