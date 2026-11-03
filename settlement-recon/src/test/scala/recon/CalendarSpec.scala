package recon

import java.time.LocalDate
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CalendarSpec extends AnyFlatSpec with Matchers {

  private val calendar = new SettlementCalendar(Set(
    LocalDate.parse("2026-11-26"), // Thanksgiving
    LocalDate.parse("2026-12-25")  // Christmas
  ))

  "SettlementCalendar" should "skip weekends for T+1" in {
    // Friday Oct 30 -> Monday Nov 2
    calendar.addBusinessDays(LocalDate.parse("2026-10-30"), 1) shouldBe
      LocalDate.parse("2026-11-02")
  }

  it should "skip Thanksgiving" in {
    // Wednesday Nov 25 -> Friday Nov 27
    calendar.addBusinessDays(LocalDate.parse("2026-11-25"), 1) shouldBe
      LocalDate.parse("2026-11-27")
  }

  it should "skip Christmas and the weekend" in {
    // Thursday Dec 24 -> Monday Dec 28
    calendar.addBusinessDays(LocalDate.parse("2026-12-24"), 1) shouldBe
      LocalDate.parse("2026-12-28")
  }

  it should "treat Saturday and Sunday as non-business days" in {
    calendar.isBusinessDay(LocalDate.parse("2026-10-31")) shouldBe false
    calendar.isBusinessDay(LocalDate.parse("2026-11-01")) shouldBe false
    calendar.isBusinessDay(LocalDate.parse("2026-11-02")) shouldBe true
  }

  it should "support T+2 as well as T+1" in {
    calendar.addBusinessDays(LocalDate.parse("2026-10-29"), 2) shouldBe
      LocalDate.parse("2026-11-02")
  }
}
