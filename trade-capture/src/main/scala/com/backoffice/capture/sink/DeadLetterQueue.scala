package com.backoffice.capture.sink

import com.backoffice.capture.model.Rejection
import java.sql.{DriverManager, PreparedStatement}
import java.util.Properties
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.slf4j.LoggerFactory

case class DeadLetter(
  execId: String,
  rawMessage: String,
  rejection: Rejection,
  parsedSymbol: Option[String]
)

class DeadLetterQueue(
  jdbcUrl: String,
  user: String,
  password: String,
  kafkaBootstrap: String,
  dlqTopic: String
) {
  private val log = LoggerFactory.getLogger(getClass)

  private val producer: KafkaProducer[String, String] = {
    val props = new Properties()
    props.put("bootstrap.servers", kafkaBootstrap)
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("acks", "all")
    new KafkaProducer[String, String](props)
  }

  /** Persist a rejection to Postgres and publish it to the DLQ topic. */
  def send(letter: DeadLetter): Unit = {
    writeToPostgres(letter)
    publishToKafka(letter)
    log.warn("Trade rejected: execId={} code={} detail={}",
      letter.execId, letter.rejection.code, letter.rejection.detail)
  }

  private def writeToPostgres(letter: DeadLetter): Unit = {
    val conn = DriverManager.getConnection(jdbcUrl, user, password)
    try {
      val stmt: PreparedStatement = conn.prepareStatement(
        """INSERT INTO rejected_trades
          |  (exec_id, raw_message, rejection_code, rejection_detail)
          |VALUES (?, ?, ?, ?)
          |ON CONFLICT (exec_id) DO UPDATE
          |  SET rejection_code = EXCLUDED.rejection_code,
          |      rejection_detail = EXCLUDED.rejection_detail,
          |      received_at = NOW()""".stripMargin)
      stmt.setString(1, letter.execId)
      stmt.setString(2, letter.rawMessage)
      stmt.setString(3, letter.rejection.code)
      stmt.setString(4, letter.rejection.detail)
      stmt.executeUpdate()
    } finally {
      conn.close()
    }
  }

  private def publishToKafka(letter: DeadLetter): Unit = {
    val json =
      s"""{"execId":"${letter.execId}","code":"${letter.rejection.code}","detail":"${letter.rejection.detail.replace("\"", "'")}","raw":"${letter.rawMessage.replace("\"", "'")}"}"""
    producer.send(new ProducerRecord[String, String](dlqTopic, letter.execId, json))
  }

  def close(): Unit = producer.close()
}
