package recon

import java.time.{DayOfWeek, LocalDate}
import scala.io.Source

/** Business-day calendar for one market. */
class SettlementCalendar(holidays: Set[LocalDate]) {

  def isBusinessDay(date: LocalDate): Boolean = {
    val weekend = date.getDayOfWeek == DayOfWeek.SATURDAY ||
      date.getDayOfWeek == DayOfWeek.SUNDAY
    !weekend && !holidays.contains(date)
  }

  /** Roll forward to the next business day, strictly after the given date. */
  def nextBusinessDay(date: LocalDate): LocalDate = {
    var candidate = date.plusDays(1)
    while (!isBusinessDay(candidate)) candidate = candidate.plusDays(1)
    candidate
  }

  /** Add n business days to a date (T+n settlement). */
  def addBusinessDays(date: LocalDate, n: Int): LocalDate = {
    var result = date
    var remaining = n
    while (remaining > 0) {
      result = nextBusinessDay(result)
      remaining -= 1
    }
    result
  }
}

object SettlementCalendar {
  /** Load holidays from a resource file, one ISO date per line, '#' comments. */
  def fromResource(path: String): SettlementCalendar = {
    val lines = Source.fromResource(path).getLines().toList
    val dates = lines
      .map(_.trim)
      .filter(line => line.nonEmpty && !line.startsWith("#"))
      .map(LocalDate.parse)
      .toSet
    new SettlementCalendar(dates)
  }
}
