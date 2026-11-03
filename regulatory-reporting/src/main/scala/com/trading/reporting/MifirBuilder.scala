package com.trading.reporting

import java.time.LocalDate

object MifirBuilder {

  /** Transaction reference number: firm + trade id, stable across corrections. */
  def transactionReferenceNumber(trade: Trade): String =
    s"${trade.firmId}-${trade.tradeId}"

  /**
   * Derive buyer and seller identification from side, capacity, and parties.
   *
   * Rules (simplified MiFIR logic):
   *  - Buy as agent for a client: buyer = client, seller = counterparty.
   *  - Buy as principal: buyer = firm, seller = counterparty.
   *  - Sell as agent for a client: buyer = counterparty, seller = client.
   *  - Sell as principal: buyer = counterparty, seller = firm.
   *  - A missing counterparty is reported as the venue (OTC trades aside).
   *  - Identification prefers LEI for legal persons, national id for
   *    natural persons, and falls back to the internal party id.
   */
  def identifyParty(party: Option[PartyReference], firmId: String): String =
    party match {
      case Some(p) if p.lei.isDefined       => p.lei.get
      case Some(p) if p.nationalId.isDefined => p.nationalId.get
      case Some(p)                           => p.partyId
      case None                              => firmId
    }

  def buyerAndSeller(et: EnrichedTrade): (String, String) = {
    val trade = et.trade
    val firm = trade.firmId
    val clientId = identifyParty(et.client, firm)
    val counterpartyId = et.counterparty.map(c => identifyParty(Some(c), firm))
      .getOrElse(trade.executionVenue)
    (trade.side, trade.capacity) match {
      case (Side.Buy, Capacity.Agency)            => (clientId, counterpartyId)
      case (Side.Buy, _)                          => (firm, counterpartyId)
      case (Side.Sell, Capacity.Agency)           => (counterpartyId, clientId)
      case (Side.Sell, _)                         => (counterpartyId, firm)
      case (Side.SellShort, Capacity.Agency)      => (counterpartyId, clientId)
      case (Side.SellShort, _)                    => (counterpartyId, firm)
    }
  }

  def shortSellingIndicator(trade: Trade): String =
    if (trade.isShortSale) "SESH" else "NOSE"

  /**
   * Build one MiFIR record per trade. Only MiFIR-eligible instruments
   * produce records. A trade with no decision maker is still reported
   * here; validation in Step 5 will flag it, because reporting a trade
   * with a missing decision maker is an error, not a reason to drop it.
   */
  def build(
    enriched: List[EnrichedTrade]
  ): List[MifirReport] = {
    enriched
      .filter(_.instrument.isEligibleForMifir)
      .map { et =>
        val trade = et.trade
        val (buyer, seller) = buyerAndSeller(et)
        val instrumentId = et.instrument.isin.getOrElse(trade.symbol)
        MifirReport(
          transactionReferenceNumber = transactionReferenceNumber(trade),
          tradingVenue = trade.executionVenue,
          instrumentIdentification = instrumentId,
          price = trade.price,
          quantity = trade.quantity,
          transactionDateTime = trade.executionTimestamp,
          buyerIdentification = buyer,
          sellerIdentification = seller,
          investmentDecisionMaker = trade.decisionMakerId.getOrElse(""),
          executingPerson = trade.executingTraderId.getOrElse(""),
          countryOfBranch = et.client.map(_.countryCode).getOrElse("US"),
          waiverIndicator = "false",
          shortSellingIndicator = shortSellingIndicator(trade),
          commodityDerivativeIndicator = "false",
          reportingDate = et.reportingDate,
          version = 1,
          originalReportId = None
        )
      }
  }
}
