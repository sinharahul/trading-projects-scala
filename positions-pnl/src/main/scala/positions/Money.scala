package positions

import scala.math.BigDecimal.RoundingMode

/** Money helpers. All money in this project is BigDecimal, never Double.
  * Amounts are in major units (dollars) with scale 2; per-share prices
  * use scale 6 so that average-cost division does not lose precision
  * before the final rounding to cents.
  */
object Money {
  val MoneyScale = 2
  val PriceScale = 6

  def money(value: BigDecimal): BigDecimal =
    value.setScale(MoneyScale, RoundingMode.HALF_UP)

  def price(value: BigDecimal): BigDecimal =
    value.setScale(PriceScale, RoundingMode.HALF_UP)

  /** Total cost of a quantity at a per-share price, rounded to cents. */
  def cost(quantity: Long, pricePerShare: BigDecimal): BigDecimal =
    money(BigDecimal(quantity) * pricePerShare)

  /** Average cost = total cost / quantity, kept at price scale.
    * Callers round to cents only when reporting a money amount.
    */
  def averageCost(totalCost: BigDecimal, quantity: Long): BigDecimal = {
    require(quantity != 0, "average cost is undefined for a flat position")
    (totalCost / BigDecimal(quantity))
      .setScale(PriceScale, RoundingMode.HALF_UP)
  }
}
