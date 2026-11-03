package com.trading.surveillance.backtest

import com.trading.surveillance.model.{OrderEvent, Trade}
import java.sql.Timestamp
import java.time.{LocalDate, LocalDateTime, LocalTime}
import scala.util.Random

/** Deterministic synthetic market data for surveillance development and backtesting.
  *
  * The generator produces a full trading month (configurable) of order events and
  * trades for a small universe of symbols. Normal flow is generated first; planted
  * scenarios are then layered on top, and every planted event is recorded in a
  * ground-truth log so the backtest can score detectors against it.
  *
  * @param seed fixed seed for reproducibility
  * @param symbols the traded universe
  * @param startDate first trading day
  * @param tradingDays number of trading days to generate
  */
class SyntheticDataGenerator(
  seed: Long,
  symbols: Seq[String] = Seq("ACME", "GLOB", "INNO", "VERT", "NOVA"),
  startDate: LocalDate = LocalDate.of(2026, 9, 1),
  tradingDays: Int = 22
) {
  private val rng = new Random(seed)

  // Fair values and typical daily volumes, per symbol. Quiet names matter:
  // a marking-the-close detector must behave differently on ACME (busy) and
  // VERT (quiet), and the generator encodes that difference.
  private val fairValue: Map[String, Double] = Map(
    "ACME" -> 152.40, "GLOB" -> 87.15, "INNO" -> 210.80,
    "VERT" -> 45.30, "NOVA" -> 310.55
  )
  private val typicalDailyVolume: Map[String, Long] = Map(
    "ACME" -> 1200000L, "GLOB" -> 800000L, "INNO" -> 450000L,
    "VERT" -> 90000L, "NOVA" -> 600000L
  )

  private val normalAccounts: Seq[String] =
    (1 to 40).map(i => f"ACC-$i%03d")
  private val normalOwners: Map[String, String] =
    normalAccounts.map(a => a -> s"OWN-${a.drop(4)}").toMap

  /** One trading day runs 09:30 to 16:00 local time. */
  private def dayMinutes(day: LocalDate): Seq[LocalDateTime] = {
    val open = LocalDateTime.of(day, LocalTime.of(9, 30))
    (0 until 390).map(m => open.plusMinutes(m))
  }

  /** Volume profile across the day: U-shaped, heavy at open and close. */
  private def volumeWeight(minuteOfDay: Int): Double = {
    val t = minuteOfDay / 390.0
    0.4 + 2.2 * math.pow(math.abs(t - 0.5) * 2, 2)
  }

  /** Generate one day of normal order events and trades for one symbol. */
  def normalDay(symbol: String, day: LocalDate): (Seq[OrderEvent], Seq[Trade]) = {
    val orders = scala.collection.mutable.ArrayBuffer.empty[OrderEvent]
    val trades = scala.collection.mutable.ArrayBuffer.empty[Trade]
    val basePrice = fairValue(symbol)
    var price = basePrice
    var orderSeq = 0L
    var tradeSeq = 0L

    val targetTrades = (typicalDailyVolume(symbol) / 400).toInt
    val minutes = dayMinutes(day)

    var produced = 0
    while (produced < targetTrades) {
      val minuteIdx = rng.nextInt(390)
      val minute = minutes(minuteIdx)
      // Random walk: small steps, mean-reverting toward fair value.
      price += (rng.nextGaussian() * 0.06) + (basePrice - price) * 0.002
      val account = normalAccounts(rng.nextInt(normalAccounts.size))
      val side = if (rng.nextBoolean()) "BUY" else "SELL"
      val qty = (100 + rng.nextInt(900)).toLong
      val px = math.round(price * 100.0) / 100.0
      orderSeq += 1
      val orderId = s"ORD-$symbol-$day-$orderSeq"
      orders += OrderEvent(
        orderId = orderId,
        accountId = account,
        beneficialOwnerId = normalOwners(account),
        controllerId = s"CTRL-$account",
        symbol = symbol,
        side = side,
        orderType = "LIMIT",
        quantity = qty,
        price = px,
        eventType = "PLACED",
        eventTime = Timestamp.valueOf(minute)
      )
      // Most orders fill, some cancel — the realistic mix.
      if (rng.nextDouble() < 0.75) {
        tradeSeq += 1
        val counter = normalAccounts(rng.nextInt(normalAccounts.size))
        trades += Trade(
          tradeId = s"TRD-$symbol-$day-$tradeSeq",
          symbol = symbol,
          price = px,
          quantity = qty,
          buyOrderId = if (side == "BUY") orderId else s"ORD-$symbol-$day-X$tradeSeq",
          sellOrderId = if (side == "SELL") orderId else s"ORD-$symbol-$day-X$tradeSeq",
          buyAccountId = if (side == "BUY") account else counter,
          sellAccountId = if (side == "SELL") account else counter,
          buyBeneficialOwnerId =
            if (side == "BUY") normalOwners(account) else normalOwners(counter),
          sellBeneficialOwnerId =
            if (side == "SELL") normalOwners(account) else normalOwners(counter),
          tradeTime = Timestamp.valueOf(minute.plusSeconds(rng.nextInt(30)))
        )
        orders += OrderEvent(
          orderId = orderId,
          accountId = account,
          beneficialOwnerId = normalOwners(account),
          controllerId = s"CTRL-$account",
          symbol = symbol,
          side = side,
          orderType = "LIMIT",
          quantity = qty,
          price = px,
          eventType = "FILLED",
          eventTime = Timestamp.valueOf(minute.plusSeconds(rng.nextInt(30)))
        )
      } else {
        orders += OrderEvent(
          orderId = orderId,
          accountId = account,
          beneficialOwnerId = normalOwners(account),
          controllerId = s"CTRL-$account",
          symbol = symbol,
          side = side,
          orderType = "LIMIT",
          quantity = qty,
          price = px,
          eventType = "CANCELLED",
          eventTime = Timestamp.valueOf(minute.plusSeconds(60 + rng.nextInt(600)))
        )
      }
      produced += 1
    }
    (orders.toSeq, trades.toSeq)
  }
}
