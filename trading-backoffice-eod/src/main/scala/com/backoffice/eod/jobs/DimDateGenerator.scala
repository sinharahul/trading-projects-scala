package com.backoffice.eod.jobs

import java.time.{DayOfWeek, LocalDate}
import org.apache.spark.sql.SparkSession

/** Generates dim_date for a ten-year window.
  *
  * Business days are Monday–Friday excluding the US market holiday
  * list passed in. The sample system uses a small fixed holiday set;
  * a production system loads the exchange calendar.
  */
object DimDateGenerator {
  val UsHolidays2026: Set[LocalDate] = Set(
    LocalDate.of(2026, 1, 1),
    LocalDate.of(2026, 1, 19),
    LocalDate.of(2026, 2, 16),
    LocalDate.of(2026, 4, 3),
    LocalDate.of(2026, 5, 25),
    LocalDate.of(2026, 6, 19),
    LocalDate.of(2026, 7, 3),
    LocalDate.of(2026, 9, 7),
    LocalDate.of(2026, 11, 26),
    LocalDate.of(2026, 12, 25)
  )

  def isBusinessDay(d: LocalDate, holidays: Set[LocalDate]): Boolean =
    d.getDayOfWeek != DayOfWeek.SATURDAY &&
      d.getDayOfWeek != DayOfWeek.SUNDAY &&
      !holidays.contains(d)

  def run(spark: SparkSession, holidays: Set[LocalDate] = UsHolidays2026): Unit = {
    import spark.implicits._
    val start = LocalDate.of(2025, 1, 1)
    val end = LocalDate.of(2035, 12, 31)
    val allDays = Iterator.iterate(start)(_.plusDays(1)).takeWhile(!_.isAfter(end)).toSeq
    val businessDays = allDays.filter(isBusinessDay(_, holidays))
    val rows = allDays.map { d =>
      val idx = businessDays.indexWhere(!_.isAfter(d))
      val prev = if (idx > 0) businessDays(idx - 1) else null
      val next = businessDays.find(_.isAfter(d)).orNull
      val lastOfMonth = d.plusDays(1).getMonth != d.getMonth
      val lastOfQuarter = lastOfMonth && Set(3, 6, 9, 12).contains(d.getMonthValue)
      val lastOfYear = d.getMonthValue == 12 && d.getDayOfMonth == 31
      (d.toString, d.getYear, (d.getMonthValue - 1) / 3 + 1, d.getMonthValue,
        d.getDayOfMonth, d.getDayOfWeek.getValue, isBusinessDay(d, holidays),
        lastOfMonth, lastOfQuarter, lastOfYear,
        Option(prev).map(_.toString).orNull, Option(next).map(_.toString).orNull)
    }
    val df = rows.toDF("calendar_date", "year", "quarter", "month", "day",
      "day_of_week", "is_business_day", "is_month_end", "is_quarter_end",
      "is_year_end", "prev_business_date", "next_business_date")
    df.write.format("delta").mode("overwrite").saveAsTable("gold.dim_date")
  }
}
