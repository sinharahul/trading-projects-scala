package com.trading.surveillance.streaming

import com.trading.surveillance.backtest.{PlantedScenarios, SyntheticDataGenerator}
import com.trading.surveillance.model.{OrderEvent, Trade}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer
import java.sql.Timestamp
import java.time.LocalDate
import java.util.Properties
import scala.util.Random

/** Publishes synthetic orders and trades to Kafka in timestamp order.
  * JSON serialisation is done by hand here (no JSON library) so the producer
  * has zero extra dependencies; the field names must match Schemas exactly.
  */
object KafkaProducer {
  private def orderToJson(o: OrderEvent): String = {
    val device = o.deviceFingerprint.map(d => s""""deviceFingerprint":"$d"""")
      .getOrElse(""""deviceFingerprint":null""")
    s"""{"orderId":"${o.orderId}","accountId":"${o.accountId}",""" +
    s""""beneficialOwnerId":"${o.beneficialOwnerId}",""" +
    s""""controllerId":"${o.controllerId}","symbol":"${o.symbol}",""" +
    s""""side":"${o.side}","orderType":"${o.orderType}",""" +
    s""""quantity":${o.quantity},"price":${o.price},""" +
    s""""eventType":"${o.eventType}","eventTime":"${o.eventTime}",$device}"""
  }

  private def tradeToJson(t: Trade): String = {
    s"""{"tradeId":"${t.tradeId}","symbol":"${t.symbol}",""" +
    s""""price":${t.price},"quantity":${t.quantity},""" +
    s""""buyOrderId":"${t.buyOrderId}","sellOrderId":"${t.sellOrderId}",""" +
    s""""buyAccountId":"${t.buyAccountId}","sellAccountId":"${t.sellAccountId}",""" +
    s""""buyBeneficialOwnerId":"${t.buyBeneficialOwnerId}",""" +
    s""""sellBeneficialOwnerId":"${t.sellBeneficialOwnerId}",""" +
    s""""tradeTime":"${t.tradeTime}","venue":"${t.venue}"}"""
  }

  def main(args: Array[String]): Unit = {
    val brokers = sys.env.getOrElse("KAFKA_BROKERS", "localhost:9092")
    val seed = if (args.length > 0) args(0).toLong else 42L
    val days = if (args.length > 1) args(1).toInt else 5

    val props = new Properties()
    props.put("bootstrap.servers", brokers)
    props.put("key.serializer", classOf[StringSerializer].getName)
    props.put("value.serializer", classOf[StringSerializer].getName)
    props.put("acks", "all")
    val producer = new KafkaProducer[String, String](props)

    val gen = new SyntheticDataGenerator(seed = seed)
    val start = LocalDate.of(2026, 9, 1)
    val dayList = (0 until days).map(start.plusDays(_))

    // Normal flow.
    val normalOrders = dayList.flatMap(d =>
      gen.symbols.flatMap(s => gen.normalDay(s, d)._1))
    val normalTrades = dayList.flatMap(d =>
      gen.symbols.flatMap(s => gen.normalDay(s, d)._2))

    // Planted scenarios on day 3 and day 4, so the stream contains abuse
    // early enough to see alerts without waiting for the full month.
    val (washTrades, _) = PlantedScenarios.washRing(start.plusDays(2))
    val (spoofEvents, _) = PlantedScenarios.spoofingEpisode(start.plusDays(3))

    val orders = (normalOrders ++ spoofEvents)
      .sortBy(_.eventTime.getTime)
    val trades = (normalTrades ++ washTrades)
      .sortBy(_.tradeTime.getTime)

    println(s"Publishing ${orders.size} order events and ${trades.size} trades.")
    orders.foreach { o =>
      producer.send(new ProducerRecord[String, String](
        "orders", o.orderId, orderToJson(o)))
    }
    trades.foreach { t =>
      producer.send(new ProducerRecord[String, String](
        "trades", t.tradeId, tradeToJson(t)))
    }
    producer.flush()
    producer.close()
    println("Done.")
  }
}
