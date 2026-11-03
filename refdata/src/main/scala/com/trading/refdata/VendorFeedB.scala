package com.trading.refdata

import java.time.LocalDate
import java.security.MessageDigest
import scala.io.Source

object VendorFeedB {
  private def opt(v: ujson.Value): Option[String] = v match {
    case ujson.Str(s) if s.trim.nonEmpty => Some(s.trim)
    case _ => None
  }

  private def str(v: ujson.Value, field: String): String =
    v.obj.get(field).map {
      case ujson.Str(s) => s
      case other => other.toString
    }.getOrElse("")

  private def hashRow(values: Seq[String]): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(values.mkString("|").getBytes("UTF-8"))
      .map("%02x".format(_)).mkString
  }

  /** Reads Vendor B's JSON array into staged VendorRecords. */
  def read(path: String): Seq[VendorRecord] = {
    val text = Source.fromFile(path).mkString
    val arr = ujson.read(text).arr
    arr.map { item =>
      val ids = item.obj.get("identifiers").map(_.obj).getOrElse(Map.empty)
      val issuer = item.obj.get("issuer").map(_.obj).getOrElse(Map.empty)
      def idVal(field: String): Option[String] =
        ids.get(field).flatMap(opt)
      def issuerVal(field: String): String =
        issuer.get(field).map {
          case ujson.Str(s) => s
          case other => other.toString
        }.getOrElse("")
      def issuerOpt(field: String): Option[String] =
        issuer.get(field).flatMap(opt)
      val values = Seq(
        str(item, "id"), str(item, "companyName"),
        idVal("cusip").getOrElse(""), idVal("isin").getOrElse(""),
        idVal("figi").getOrElse(""), str(item, "ticker"),
        str(item, "exchange"), str(item, "assetClass"),
        str(item, "type"), str(item, "currency"),
        issuerVal("name"), issuerOpt("lei").getOrElse(""),
        issuerVal("country"), issuerVal("sector"),
        str(item, "status"), str(item, "asOf")
      )
      VendorRecord(
        vendor = "VENDOR_B",
        vendorSecurityId = str(item, "id"),
        name = str(item, "companyName"),
        cusip = idVal("cusip"),
        isin = idVal("isin"),
        sedol = None, // Vendor B does not supply SEDOLs
        figi = idVal("figi"),
        ticker = Some(str(item, "ticker")).filter(_.nonEmpty),
        mic = Some(str(item, "exchange")).filter(_.nonEmpty),
        assetClass = str(item, "assetClass"),
        securityType = str(item, "type"),
        currency = str(item, "currency"),
        issuerName = issuerVal("name"),
        issuerLei = issuerOpt("lei"),
        country = issuerVal("country"),
        sector = issuerVal("sector"),
        status = str(item, "status"),
        asOfDate = LocalDate.parse(str(item, "asOf")),
        rawHash = hashRow(values)
      )
    }.toSeq
  }
}
