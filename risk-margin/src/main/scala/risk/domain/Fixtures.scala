package risk.domain

/** Shared fixtures for tests and worked examples. */
object Fixtures {

  // Three symbols, chosen so that arithmetic stays checkable by hand.
  val infos: Map[String, SymbolInfo] = Map(
    "ACME" -> SymbolInfo("ACME", "Technology", beta = 1.20),
    "GLOB" -> SymbolInfo("GLOB", "Healthcare", beta = 0.80),
    "STAB" -> SymbolInfo("STAB", "Utilities",  beta = 0.50)
  )

  /** Account A1, desk DESK-1: long ACME, long GLOB, short STAB. */
  val bookA1: List[Position] = List(
    Position("A1", "DESK-1", "ACME", BigDecimal(1000), BigDecimal(100)),
    Position("A1", "DESK-1", "GLOB", BigDecimal(500),  BigDecimal(80)),
    Position("A1", "DESK-1", "STAB", BigDecimal(-400), BigDecimal(50))
  )

  /** Closing prices for the book's valuation date. */
  val prices: Map[String, BigDecimal] = Map(
    "ACME" -> BigDecimal(120),
    "GLOB" -> BigDecimal(90),
    "STAB" -> BigDecimal(55)
  )

  val accountA1: Account =
    Account("A1", "DESK-1", AccountType.RegTMargin, BigDecimal(60000))
}
