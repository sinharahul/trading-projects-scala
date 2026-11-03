package recon

import scala.io.Source

/** One standing settlement instruction. */
case class Ssi(
  ssiId: String,
  custodian: String,
  currency: String,
  market: String,
  account: String
)

/** SSI lookup with explicit failure instead of silent guessing. */
class SsiRepository(entries: Seq[Ssi]) {

  private val byKey: Map[(String, String, String), Ssi] =
    entries.map(e => (e.custodian, e.currency, e.market) -> e).toMap

  private val byCustodianCurrency: Map[(String, String), Ssi] =
    entries.map(e => (e.custodian, e.currency) -> e).toMap

  /** Resolve the SSI for a trade. Exact market match first, then
    * custodian+currency fallback. Throws if nothing matches, because
    * settling into a guessed account is worse than failing loudly. */
  def resolve(custodian: String, currency: String, market: String): Ssi = {
    byKey.get((custodian, currency, market))
      .orElse(byCustodianCurrency.get((custodian, currency)))
      .getOrElse(
        throw new IllegalArgumentException(
          s"No SSI for custodian=$custodian currency=$currency market=$market"
        )
      )
  }
}

object SsiRepository {
  /** CSV format: ssiId,custodian,currency,market,account (header row first). */
  def fromResource(path: String): SsiRepository = {
    val lines = Source.fromResource(path).getLines().toList
    val entries = lines.drop(1).filter(_.trim.nonEmpty).map { line =>
      val cols = line.split(",", -1).map(_.trim)
      Ssi(cols(0), cols(1), cols(2), cols(3), cols(4))
    }
    new SsiRepository(entries)
  }
}
