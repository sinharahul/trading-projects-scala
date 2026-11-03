package com.backoffice.marketdata.generator

import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.util.Random

/** One market event, before serialization. The producer converts this
  * to JSON; the streaming job parses the JSON back into a tick.
  */
case class Tick(
  eventType: String,
  symbol: String,
  exchange: String,
  eventTime: Instant,
  sequenceNumber: Long,
  tradePrice: Option[Double],
  tradeSize: Option[Long],
  tradeCondition: Option[String],
  bidPrice: Option[Double],
  bidSize: Option[Long],
  askPrice: Option[Double],
  askSize: Option[Long]
)

object TickGenerator {

  val Symbols: Seq[String] = Seq("AAPL", "MSFT", "JPM", "XOM", "TSLA")

  /** Opening reference prices, roughly realistic for late 2026. These are
    * starting points for the random walk, not real market data.
    */
  val BasePrices: Map[String, Double] = Map(
    "AAPL" -> 262.40,
    "MSFT" -> 512.15,
    "JPM" -> 298.70,
    "XOM" -> 118.25,
    "TSLA" -> 341.80
  )

  private val formatter =
    DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

  /** Generate `count` ticks starting at `start`, one tick roughly every
    * `intervalSeconds` of event time. Prices follow a small random walk so
    * that bars look plausible: open, high, low, and close differ, but not
    * wildly. A fixed seed makes runs reproducible, which matters when you
    * are debugging: the same seed produces the same data.
    */
  def generate(
    count: Int,
    start: Instant,
    intervalSeconds: Int = 2,
    seed: Long = 42L,
    defectRate: Double = 0.02
  ): Seq[Tick] = {
    val rng = new Random(seed)
    val lastPrice = scala.collection.mutable.Map(BasePrices.toSeq: _*)
    val lastSeq = scala.collection.mutable.Map[String, Long]()
      .withDefaultValue(0L)
    val out = scala.collection.mutable.ArrayBuffer.empty[Tick]

    var i = 0
    while (i < count) {
      val symbol = Symbols(rng.nextInt(Symbols.length))
      val eventTime = start.plusSeconds(i.toLong * intervalSeconds)
      val seq = lastSeq(symbol) + 1
      lastSeq(symbol) = seq

      // Random-walk step: at most about 0.05% per tick, so a minute of
      // ticks moves the price by a plausible fraction of a percent.
      val step = (rng.nextGaussian() * 0.0005) * lastPrice(symbol)
      val price = BigDecimal(lastPrice(symbol) + step)
        .setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
      lastPrice(symbol) = price

      val isTrade = rng.nextDouble() < 0.7
      val tick =
        if (isTrade) {
          Tick(
            eventType = "TRADE",
            symbol = symbol,
            exchange = "NASDAQ",
            eventTime = eventTime,
            sequenceNumber = seq,
            tradePrice = Some(price),
            tradeSize = Some(100L + rng.nextInt(900)),
            tradeCondition = Some(pickCondition(rng)),
            bidPrice = None, bidSize = None,
            askPrice = None, askSize = None
          )
        } else {
          val spread = 0.01 + rng.nextDouble() * 0.04
          Tick(
            eventType = "QUOTE",
            symbol = symbol,
            exchange = "NASDAQ",
            eventTime = eventTime,
            sequenceNumber = seq,
            tradePrice = None, tradeSize = None, tradeCondition = None,
            bidPrice = Some(round2(price - spread / 2)),
            bidSize = Some(100L * (1 + rng.nextInt(50))),
            askPrice = Some(round2(price + spread / 2)),
            askSize = Some(100L * (1 + rng.nextInt(50)))
          )
        }
      out += tick

      // Defect injection: with probability defectRate, emit a duplicate
      // of this tick (same symbol, exchange, sequence number) or a late
      // tick stamped 90 seconds in the past. These are the records Step 4
      // and Step 5 must handle. In production they come from the feed;
      // here you control them, which makes testing possible.
      if (rng.nextDouble() < defectRate) {
        if (rng.nextBoolean()) {
          out += tick // exact duplicate
        } else {
          out += tick.copy(eventTime = eventTime.minusSeconds(90))
        }
      }
      i += 1
    }
    out.toSeq
  }

  private def pickCondition(rng: Random): String = {
    val conditions = Seq("@", "F", "I", "O", "M")
    conditions(rng.nextInt(conditions.length))
  }

  private def round2(v: Double): Double =
    BigDecimal(v).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble

  /** Render a tick as the JSON the producer sends to Kafka. Field names
    * match Schemas.tickSchema exactly; a mismatch here is the most common
    * first-run bug, and Step 4 will show you how it surfaces.
    */
  def toJson(t: Tick): String = {
    def num(o: Option[Double]): String = o.map(_.toString).getOrElse("null")
    def lng(o: Option[Long]): String = o.map(_.toString).getOrElse("null")
    def str(o: Option[String]): String = o.map(s => s""""$s"""").getOrElse("null")
    s"""{"event_type":"${t.eventType}","symbol":"${t.symbol}","exchange":"${t.exchange}","event_time":"${formatter.format(t.eventTime)}","sequence_number":${t.sequenceNumber},"trade_price":${num(t.tradePrice)},"trade_size":${lng(t.tradeSize)},"trade_condition":${str(t.tradeCondition)},"bid_price":${num(t.bidPrice)},"bid_size":${lng(t.bidSize)},"ask_price":${num(t.askPrice)},"ask_size":${lng(t.askSize)}}"""
  }
}
