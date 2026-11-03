package com.trading.refdata

import java.time.LocalDate

/** The outcome of attempting to link one vendor record. */
sealed trait MatchOutcome
object MatchOutcome {
  /** Linked to an existing golden key, with the rule that fired. */
  case class Linked(goldenKey: String, rule: String, score: Double) extends MatchOutcome
  /** No existing security matches; create a new golden key. */
  case object NewSecurity extends MatchOutcome
  /** Conflicting signals; a human or a stricter rule must decide. */
  case class NeedsReview(reason: String) extends MatchOutcome
}

object Matching {

  /** Normalise a company name for comparison: lowercase, strip
    * punctuation, drop legal suffixes, collapse whitespace.
    */
  def normaliseName(name: String): String = {
    val suffixes = Set("inc", "corp", "corporation", "ltd", "limited",
      "plc", "co", "company", "group", "holdings")
    name.toLowerCase
      .replaceAll("[^a-z0-9 ]", " ")
      .split("\\s+").filter(_.nonEmpty)
      .filterNot(suffixes.contains)
      .mkString(" ")
  }

  /** Levenshtein distance, implemented directly so the matcher has
    * no extra dependency. Inputs are expected to be normalised.
    */
  def levenshtein(a: String, b: String): Int = {
    val d = Array.tabulate(b.length + 1, a.length + 1) { (j, i) =>
      if (j == 0) i else if (i == 0) j else 0
    }
    for (j <- 1 to b.length; i <- 1 to a.length) {
      val cost = if (a(i - 1) == b(j - 1)) 0 else 1
      d(j)(i) = Seq(
        d(j - 1)(i) + 1, d(j)(i - 1) + 1, d(j - 1)(i - 1) + cost
      ).min
    }
    d(b.length)(a.length)
  }

  def nameSimilarity(a: String, b: String): Double = {
    val na = normaliseName(a)
    val nb = normaliseName(b)
    if (na.isEmpty || nb.isEmpty) 0.0
    else {
      val maxLen = math.max(na.length, nb.length)
      1.0 - levenshtein(na, nb).toDouble / maxLen
    }
  }

  /** A record's primary identifiers, as (type, value) pairs, but only
    * those that pass check-digit validation. An invalid identifier
    * is not a matching signal; it is a data quality finding.
    */
  def validPrimaryIds(r: VendorRecord): Seq[(IdentifierType, String)] = {
    val cusip = r.cusip.filter(Validation.isValidCusip).map(IdentifierType.Cusip -> _)
    val isin = r.isin.filter(Validation.isValidIsin).map(IdentifierType.Isin -> _)
    val sedol = r.sedol.filter(Validation.isValidSedol).map(IdentifierType.Sedol -> _)
    val figi = r.figi.map(IdentifierType.Figi -> _)
    Seq(cusip, isin, sedol, figi).flatten
  }

  /** Conflict check: two records that share an identifier but whose
    * tickers differ AND whose names are dissimilar are suspicious.
    * Either the identifier is stale or the ticker is recycled.
    */
  def isConflicting(a: VendorRecord, b: VendorRecord): Boolean = {
    val tickerConflict = (a.ticker, b.ticker) match {
      case (Some(t1), Some(t2)) => t1 != t2
      case _ => false
    }
    val nameScore = nameSimilarity(a.name, b.name)
    tickerConflict && nameScore < 0.50
  }

  /** Link one incoming record against the already-linked records of
    * this load plus the cross-reference table (represented here as a
    * lookup function so the core stays testable without a database).
    *
    * `xrefLookup` maps (idType, idValue) to the golden keys that
    * identifier has ever denoted, newest first.
    */
  def link(
    incoming: VendorRecord,
    existing: Seq[VendorRecord],
    goldenKeyOf: VendorRecord => Option[String],
    xrefLookup: (IdentifierType, String) => Seq[String]
  ): MatchOutcome = {
    val myIds = validPrimaryIds(incoming)

    // Tier 1: exact primary identifier shared with an existing record.
    for ((idType, value) <- myIds) {
      val candidates = existing.filter { e =>
        validPrimaryIds(e).contains((idType, value))
      }
      if (candidates.nonEmpty) {
        val conflicting = candidates.filter(c => isConflicting(incoming, c))
        if (conflicting.nonEmpty) {
          return MatchOutcome.NeedsReview(
            s"Identifier $idType=$value shared but ticker/name conflict " +
            s"with ${conflicting.map(_.vendorSecurityId).mkString(",")}")
        }
        val key = candidates.flatMap(goldenKeyOf).headOption
        if (key.isDefined)
          return MatchOutcome.Linked(key.get, s"exact-$idType", 1.0)
      }
    }

    // Tier 2: cross-reference lookup, newest valid row wins.
    for ((idType, value) <- myIds) {
      val keys = xrefLookup(idType, value)
      if (keys.nonEmpty) {
        return MatchOutcome.Linked(keys.head, s"xref-$idType", 0.95)
      }
    }

    // Tier 3: ticker + MIC + name similarity.
    val tickerMatches = existing.filter { e =>
      e.ticker.isDefined && e.ticker == incoming.ticker &&
        e.mic.isDefined && e.mic == incoming.mic
    }
    val scored = tickerMatches.map { e =>
      (e, nameSimilarity(e.name, incoming.name))
    }.filter(_._2 >= 0.80)
    if (scored.nonEmpty) {
      val best = scored.maxBy(_._2)
      val key = goldenKeyOf(best._1)
      if (key.isDefined)
        return MatchOutcome.Linked(key.get, "ticker-mic-name", best._2)
    }

    MatchOutcome.NewSecurity
  }
}
