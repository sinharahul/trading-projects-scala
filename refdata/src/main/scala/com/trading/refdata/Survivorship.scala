package com.trading.refdata

import java.time.LocalDate

/** For one field: the winning value, who supplied it, and the
  * values that lost. `losers` is what makes survivorship auditable.
  */
case class SurvivedField[T](
  value: T,
  winningSource: String,
  asOf: LocalDate,
  losers: Seq[(String, T)]
)

/** A fully survived golden security, before SCD handling. */
case class GoldenSecurity(
  goldenKey: String,
  name: SurvivedField[String],
  cusip: SurvivedField[Option[String]],
  isin: SurvivedField[Option[String]],
  sedol: SurvivedField[Option[String]],
  figi: SurvivedField[Option[String]],
  ticker: SurvivedField[Option[String]],
  mic: SurvivedField[Option[String]],
  assetClass: SurvivedField[String],
  securityType: SurvivedField[String],
  currency: SurvivedField[String],
  issuerName: SurvivedField[String],
  issuerLei: SurvivedField[Option[String]],
  country: SurvivedField[String],
  sector: SurvivedField[String],
  status: SurvivedField[String]
)

object Survivorship {

  /** Source priority: first source in `order` with a valid value wins.
    * `extract` pulls the field from a record; `isValid` rejects
    * values that must never win (for example, invalid identifiers).
    */
  def byPriority[T](
    records: Seq[VendorRecord],
    order: Seq[String],
    extract: VendorRecord => Option[T],
    isValid: T => Boolean = (_: T) => true
  ): Option[SurvivedField[T]] = {
    val candidates = records.flatMap { r =>
      extract(r).filter(isValid).map(v => (r.vendor, r.asOfDate, v))
    }
    val ordered = order.flatMap { source =>
      candidates.filter(_._1 == source)
    }
    ordered.headOption.map { case (source, asOf, value) =>
      val losers = candidates.filter(_._3 != value).map(c => (c._1, c._3))
      SurvivedField(value, source, asOf, losers)
    }
  }

  /** Recency: the newest as-of date wins; ties break by priority. */
  def byRecency[T](
    records: Seq[VendorRecord],
    extract: VendorRecord => Option[T],
    tieBreak: Seq[String]
  ): Option[SurvivedField[T]] = {
    val candidates = records.flatMap { r =>
      extract(r).map(v => (r.vendor, r.asOfDate, v))
    }
    if (candidates.isEmpty) return None
    val newest = candidates.map(_._2).max
    val newestCandidates = candidates.filter(_._2 == newest)
    val winner = tieBreak.flatMap { source =>
      newestCandidates.filter(_._1 == source)
    }.headOption.orElse(newestCandidates.headOption)
    winner.map { case (source, asOf, value) =>
      val losers = candidates.filter(_._3 != value).map(c => (c._1, c._3))
      SurvivedField(value, source, asOf, losers)
    }
  }

  /** Voting: the most frequently supplied value wins; ties break by
    * the `tieBreak` order. A missing value is an abstention, never
    * a vote for blank.
    */
  def byVote[T](
    records: Seq[VendorRecord],
    extract: VendorRecord => Option[T],
    tieBreak: Seq[String]
  ): Option[SurvivedField[T]] = {
    val candidates = records.flatMap { r =>
      extract(r).map(v => (r.vendor, r.asOfDate, v))
    }
    if (candidates.isEmpty) return None
    val counts = candidates.groupBy(_._3).map { case (v, cs) => (v, cs.size) }
    val topCount = counts.values.max
    val topValues = counts.filter(_._2 == topCount).keys.toSeq
    val winner = tieBreak.flatMap { source =>
      candidates.filter(c => topValues.contains(c._3) && c._1 == source)
    }.headOption.orElse(
      candidates.filter(c => topValues.contains(c._3)).headOption
    )
    winner.map { case (source, asOf, value) =>
      val losers = candidates.filter(_._3 != value).map(c => (c._1, c._3))
      SurvivedField(value, source, asOf, losers)
    }
  }

  /** Survive one golden security from its linked vendor records. */
  def survive(goldenKey: String, records: Seq[VendorRecord]): GoldenSecurity = {
    def req[T](field: Option[SurvivedField[T]], fieldName: String): SurvivedField[T] =
      field.getOrElse(throw new IllegalStateException(
        s"No surviving value for $fieldName on $goldenKey"))
    GoldenSecurity(
      goldenKey = goldenKey,
      name = req(byPriority(records, Seq("VENDOR_A", "VENDOR_B"), r => Some(r.name)), "name"),
      cusip = req(byPriority(records, Seq("VENDOR_A", "VENDOR_B"), _.cusip, Validation.isValidCusip), "cusip"),
      isin = req(byPriority(records, Seq("VENDOR_A", "VENDOR_B"), _.isin, Validation.isValidIsin), "isin"),
      sedol = SurvivedField(
        byPriority(records, Seq("VENDOR_A", "VENDOR_B"), _.sedol, Validation.isValidSedol).map(_.value).flatten,
        byPriority(records, Seq("VENDOR_A", "VENDOR_B"), _.sedol, Validation.isValidSedol).map(_.winningSource).getOrElse("NONE"),
        records.map(_.asOfDate).max,
        Nil
      ),
      figi = SurvivedField(
        byPriority(records, Seq("VENDOR_A", "VENDOR_B"), _.figi).map(_.value).flatten,
        byPriority(records, Seq("VENDOR_A", "VENDOR_B"), _.figi).map(_.winningSource).getOrElse("NONE"),
        records.map(_.asOfDate).max,
        Nil
      ),
      ticker = SurvivedField(
        byPriority(records, Seq("VENDOR_A", "VENDOR_B"), _.ticker).map(_.value).flatten,
        byPriority(records, Seq("VENDOR_A", "VENDOR_B"), _.ticker).map(_.winningSource).getOrElse("NONE"),
        records.map(_.asOfDate).max,
        Nil
      ),
      mic = SurvivedField(
        byPriority(records, Seq("VENDOR_A", "VENDOR_B"), _.mic).map(_.value).flatten,
        byPriority(records, Seq("VENDOR_A", "VENDOR_B"), _.mic).map(_.winningSource).getOrElse("NONE"),
        records.map(_.asOfDate).max,
        Nil
      ),
      assetClass = req(byPriority(records, Seq("VENDOR_A", "VENDOR_B"), r => Some(r.assetClass)), "assetClass"),
      securityType = req(byPriority(records, Seq("VENDOR_A", "VENDOR_B"), r => Some(r.securityType)), "securityType"),
      currency = req(byPriority(records, Seq("VENDOR_A", "VENDOR_B"), r => Some(r.currency)), "currency"),
      issuerName = req(byPriority(records, Seq("VENDOR_A", "VENDOR_B"), r => Some(r.issuerName)), "issuerName"),
      issuerLei = SurvivedField(
        byPriority(records, Seq("VENDOR_B", "VENDOR_A"), _.issuerLei).map(_.value).flatten,
        byPriority(records, Seq("VENDOR_B", "VENDOR_A"), _.issuerLei).map(_.winningSource).getOrElse("NONE"),
        records.map(_.asOfDate).max,
        Nil
      ),
      country = req(byPriority(records, Seq("VENDOR_A", "VENDOR_B"), r => Some(r.country)), "country"),
      // Sector: voting, ties to Vendor B (GICS-cycle updates).
      sector = req(byVote(records, r => Some(r.sector), Seq("VENDOR_B", "VENDOR_A")), "sector"),
      status = req(byRecency(records, r => Some(r.status), Seq("VENDOR_A", "VENDOR_B")), "status")
    )
  }
}
