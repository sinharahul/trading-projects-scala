package com.trading.surveillance.model

import java.sql.Timestamp

/** One event in the lifecycle of a single order.
  *
  * @param orderId unique order identifier, stable across the order's life
  * @param accountId the trading account that placed the order
  * @param beneficialOwnerId the person or entity that ultimately benefits; two
  *                          different accountIds can share one beneficialOwnerId
  * @param controllerId the person or desk that controls the account (for wash
  *                     trades through related accounts)
  * @param symbol the traded instrument, e.g. "ACME"
  * @param side BUY or SELL
  * @param orderType LIMIT, MARKET, or ICEBERG
  * @param quantity total quantity of the order
  * @param price limit price, if any
  * @param eventType PLACED, AMENDED, PARTIALLY_FILLED, FILLED, CANCELLED, EXPIRED
  * @param eventTime when the event occurred on the exchange clock
  * @param deviceFingerprint optional fingerprint of the submitting terminal or
  *                          session; useful for linking accounts
  */
case class OrderEvent(
  orderId: String,
  accountId: String,
  beneficialOwnerId: String,
  controllerId: String,
  symbol: String,
  side: String,
  orderType: String,
  quantity: Long,
  price: Double,
  eventType: String,
  eventTime: Timestamp,
  deviceFingerprint: Option[String] = None
)

/** One executed trade (a match between a buy and a sell).
  *
  * @param tradeId unique trade identifier
  * @param symbol the traded instrument
  * @param price execution price
  * @param quantity executed quantity
  * @param buyOrderId the resting or aggressing buy order
  * @param sellOrderId the resting or aggressing sell order
  * @param buyAccountId account on the buy side
  * @param sellAccountId account on the sell side
  * @param buyBeneficialOwnerId beneficial owner on the buy side
  * @param sellBeneficialOwnerId beneficial owner on the sell side
  * @param tradeTime execution timestamp on the exchange clock
  * @param venue the execution venue; surveillance must work across venues
  */
case class Trade(
  tradeId: String,
  symbol: String,
  price: Double,
  quantity: Long,
  buyOrderId: String,
  sellOrderId: String,
  buyAccountId: String,
  sellAccountId: String,
  buyBeneficialOwnerId: String,
  sellBeneficialOwnerId: String,
  tradeTime: Timestamp,
  venue: String = "XNAS"
)
