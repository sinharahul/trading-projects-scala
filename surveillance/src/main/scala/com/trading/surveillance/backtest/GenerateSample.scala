package com.trading.surveillance.backtest

import java.time.LocalDate

object GenerateSample {
  def main(args: Array[String]): Unit = {
    val gen = new SyntheticDataGenerator(seed = 42L)
    val (orders, trades) = gen.normalDay("ACME", LocalDate.of(2026, 9, 1))
    println(s"orders=${orders.size} trades=${trades.size}")
    orders.take(5).foreach(println)
    trades.take(5).foreach(println)
    // Sanity: no trade should have the same beneficial owner on both sides
    // in pure normal flow (counterparties are drawn independently, but check).
    val suspicious = trades.count(t =>
      t.buyBeneficialOwnerId == t.sellBeneficialOwnerId)
    println(s"same-owner trades in normal flow: $suspicious")
  }
}
