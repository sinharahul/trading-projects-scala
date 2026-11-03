package com.trading.surveillance.cases

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import java.sql.Timestamp

object EvidenceReplay {
  /** Reconstruct the full event sequence for an alert's window.
    *
    * @param ordersDelta path to the order events Delta table
    * @param tradesDelta path to the trades Delta table
    * @param symbol the alert's symbol
    * @param windowStart start of the alert window
    * @param windowEnd end of the alert window
    * @param accountIds accounts to highlight (other accounts shown for context)
    * @return a unified, time-ordered event log with a flag for implicated accounts
    */
  def replay(
    spark: SparkSession,
    ordersDelta: String,
    tradesDelta: String,
    symbol: String,
    windowStart: Timestamp,
    windowEnd: Timestamp,
    accountIds: Seq[String]
  ): DataFrame = {
    import spark.implicits._

    val orders = spark.read.format("delta").load(ordersDelta)
      .filter(col("symbol") === symbol)
      .filter(col("eventTime").between(windowStart, windowEnd))
      .select(
        col("eventTime").as("ts"),
        lit("ORDER").as("eventKind"),
        col("orderId").as("id"),
        col("accountId"),
        col("side"),
        col("quantity"),
        col("price"),
        col("eventType").as("detail")
      )

    val trades = spark.read.format("delta").load(tradesDelta)
      .filter(col("symbol") === symbol)
      .filter(col("tradeTime").between(windowStart, windowEnd))
      .select(
        col("tradeTime").as("ts"),
        lit("TRADE").as("eventKind"),
        col("tradeId").as("id"),
        col("buyAccountId").as("accountId"),
        lit("BUY").as("side"),
        col("quantity"),
        col("price"),
        lit("EXECUTED").as("detail")
      )

    val implicated = spark.sparkContext.broadcast(accountIds.toSet)
    val isImplicated = udf { (account: String) =>
      implicated.value.contains(account)
    }

    orders.unionByName(trades, allowMissingColumns = true)
      .withColumn("implicated", isImplicated(col("accountId")))
      .orderBy(col("ts").asc)
  }
}
