package com.trading.surveillance.backtest

import com.trading.surveillance.model.{OrderEvent, Trade}
import java.sql.Timestamp
import java.time.{LocalDate, LocalDateTime, LocalTime}

/** One planted abuse event, with its ground-truth record.
  *
  * @param scenarioId stable identifier, e.g. "WASH-001"
  * @param detector the detector that should catch it
  * @param symbol the instrument
  * @param ownerIds owners involved
  * @param accountIds accounts involved
  * @param windowStart earliest event time
  * @param windowEnd latest event time
  * @param description what the scenario simulates
  */
case class PlantedScenario(
  scenarioId: String,
  detector: String,
  symbol: String,
  ownerIds: Seq[String],
  accountIds: Seq[String],
  windowStart: Timestamp,
  windowEnd: Timestamp,
  description: String
)

object PlantedScenarios {
  private def ts(day: LocalDate, h: Int, m: Int, s: Int = 0): Timestamp =
    Timestamp.valueOf(LocalDateTime.of(day, LocalTime.of(h, m, s)))

  /** Wash-trading ring: one owner, two accounts, 7 trades in 4 minutes. */
  def washRing(day: LocalDate): (Seq[Trade], PlantedScenario) = {
    val trades = (1 to 7).map { i =>
      Trade(
        tradeId = s"WASH-T$i",
        symbol = "ACME",
        price = 150.00,
        quantity = 1000L,
        buyOrderId = s"WASH-B$i",
        sellOrderId = s"WASH-S$i",
        buyAccountId = "ACC-201",
        sellAccountId = "ACC-202",
        buyBeneficialOwnerId = "OWN-201",
        sellBeneficialOwnerId = "OWN-201",
        tradeTime = ts(day, 10, i)
      )
    }
    val scenario = PlantedScenario(
      scenarioId = "WASH-001",
      detector = "WASH_TRADE",
      symbol = "ACME",
      ownerIds = Seq("OWN-201"),
      accountIds = Seq("ACC-201", "ACC-202"),
      windowStart = ts(day, 10, 1),
      windowEnd = ts(day, 10, 7),
      description = "One owner trading against themselves through two accounts."
    )
    (trades, scenario)
  }

  /** Spoofing episode: 12 large sell orders placed and cancelled within
    * 5 seconds, while the account buys on the other side. */
  def spoofingEpisode(day: LocalDate): (Seq[OrderEvent], PlantedScenario) = {
    val events = (1 to 12).flatMap { i =>
      val placed = OrderEvent(
        orderId = s"SPOOF-$i",
        accountId = "ACC-301",
        beneficialOwnerId = "OWN-301",
        controllerId = "CTRL-301",
        symbol = "ACME",
        side = "SELL",
        orderType = "LIMIT",
        quantity = 50000L,
        price = 150.10,
        eventType = "PLACED",
        eventTime = ts(day, 11, 0, i * 4)
      )
      val cancelled = placed.copy(
        eventType = "CANCELLED",
        eventTime = ts(day, 11, 0, i * 4 + 3)
      )
      Seq(placed, cancelled)
    }
    val scenario = PlantedScenario(
      scenarioId = "SPOOF-001",
      detector = "SPOOFING",
      symbol = "ACME",
      ownerIds = Seq("OWN-301"),
      accountIds = Seq("ACC-301"),
      windowStart = ts(day, 11, 0, 4),
      windowEnd = ts(day, 11, 0, 51),
      description = "Large sell orders cancelled within seconds, never filled."
    )
    (events, scenario)
  }

  /** Marking the close: one owner drives 75% of close-window volume in VERT
    * and pushes the price from 45.00 to 46.00 in the last 15 minutes. */
  def markingClose(day: LocalDate): (Seq[Trade], PlantedScenario) = {
    val morning = (1 to 10).map { i =>
      Trade(
        tradeId = s"MC-M$i",
        symbol = "VERT",
        price = 45.00,
        quantity = 1000L,
        buyOrderId = s"MC-MB$i",
        sellOrderId = s"MC-MS$i",
        buyAccountId = "ACC-401",
        sellAccountId = "ACC-402",
        buyBeneficialOwnerId = "OWN-401",
        sellBeneficialOwnerId = "OWN-402",
        tradeTime = ts(day, 10, i)
      )
    }
    val close = (1 to 6).map { i =>
      Trade(
        tradeId = s"MC-C$i",
        symbol = "VERT",
        price = 45.00 + i * 0.2,
        quantity = 5000L,
        buyOrderId = s"MC-CB$i",
        sellOrderId = s"MC-CS$i",
        buyAccountId = "ACC-403",
        sellAccountId = "ACC-404",
        buyBeneficialOwnerId = "OWN-403",
        sellBeneficialOwnerId = "OWN-404",
        tradeTime = ts(day, 15, 50 + i)
      )
    }
    // The marking account is the buyer in the close window: it accumulates
    // 30,000 shares (75% of close volume) while pushing the price up.
    val marking = close.map(t => t.copy(buyAccountId = "ACC-405",
      buyBeneficialOwnerId = "OWN-405"))
    val scenario = PlantedScenario(
      scenarioId = "MARK-001",
      detector = "MARKING_CLOSE",
      symbol = "VERT",
      ownerIds = Seq("OWN-405"),
      accountIds = Seq("ACC-405"),
      windowStart = ts(day, 15, 51),
      windowEnd = ts(day, 15, 56),
      description = "One owner drives the close in a quiet stock."
    )
    (morning ++ marking, scenario)
  }

  /** Unusual volume: VERT trades 900,000 shares on a day when its 30-day
    * average is 90,000. */
  def volumeSpike(day: LocalDate): (Seq[Trade], PlantedScenario) = {
    val trades = (1 to 90).map { i =>
      Trade(
        tradeId = s"VOL-$i",
        symbol = "VERT",
        price = 45.00,
        quantity = 10000L,
        buyOrderId = s"VOL-B$i",
        sellOrderId = s"VOL-S$i",
        buyAccountId = "ACC-501",
        sellAccountId = "ACC-502",
        buyBeneficialOwnerId = "OWN-501",
        sellBeneficialOwnerId = "OWN-502",
        tradeTime = ts(day, 10 + (i % 5), i % 60)
      )
    }
    val scenario = PlantedScenario(
      scenarioId = "VOL-001",
      detector = "UNUSUAL_VOLUME",
      symbol = "VERT",
      ownerIds = Seq.empty,
      accountIds = Seq.empty,
      windowStart = ts(day, 10, 0),
      windowEnd = ts(day, 15, 0),
      description = "Tenfold volume spike versus the 30-day average."
    )
    (trades, scenario)
  }

  /** Legitimate lookalike: a market maker quoting and cancelling rapidly,
    * but with a healthy fill ratio. Must NOT alert. */
  def marketMakerLookalike(day: LocalDate): Seq[OrderEvent] = {
    (1 to 20).flatMap { i =>
      val placed = OrderEvent(
        orderId = s"MM-$i",
        accountId = "ACC-601",
        beneficialOwnerId = "OWN-601",
        controllerId = "CTRL-601",
        symbol = "ACME",
        side = if (i % 2 == 0) "BUY" else "SELL",
        orderType = "LIMIT",
        quantity = 50000L,
        price = 150.05,
        eventType = "PLACED",
        eventTime = ts(day, 12, 0, i * 2)
      )
      // Half of the market maker's orders fill; the rest cancel slowly.
      if (i % 2 == 0)
        Seq(placed, placed.copy(
          eventType = "FILLED",
          eventTime = ts(day, 12, 0, i * 2 + 30)))
      else
        Seq(placed, placed.copy(
          eventType = "CANCELLED",
          eventTime = ts(day, 12, 5, i * 2)))
    }
  }
}
