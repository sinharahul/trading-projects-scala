package risk.returns

import java.time.LocalDate
import scala.io.Source
import risk.domain.Price

object PriceHistoryLoader {

  /** Load a CSV of date,symbol... columns into per-symbol price series.
    * Fails loudly on out-of-order dates or unparseable rows: a risk
    * input file that is silently wrong is worse than a crashed job.
    */
  def load(path: String): Map[String, List[Price]] = {
    val lines = Source.fromFile(path).getLines().toList
    val header = lines.head.split(",").map(_.trim).toList
    val symbols = header.tail
    require(symbols.nonEmpty, "price file must have at least one symbol column")

    val rows = lines.tail.filter(_.trim.nonEmpty).map { line =>
      val cells = line.split(",").map(_.trim).toList
      require(cells.size == header.size, s"malformed row: $line")
      val date = LocalDate.parse(cells.head)
      val closes = symbols.zip(cells.tail).map { case (sym, v) =>
        Price(sym, date, BigDecimal(v))
      }
      (date, closes)
    }

    val dates = rows.map(_._1)
    require(dates == dates.sorted && dates.distinct.size == dates.size,
      "price file dates must be strictly increasing and unique")

    symbols.map { sym =>
      sym -> rows.flatMap(_._2.filter(_.symbol == sym))
    }.toMap
  }
}
