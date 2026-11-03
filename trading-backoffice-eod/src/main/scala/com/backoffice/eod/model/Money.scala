package com.backoffice.eod.model

/** Money in integer minor units (cents). Never use Double for money.
  *
  * @param cents signed amount in cents; negative means a credit-side
  *              reduction depending on context, never an absolute value
  */
final case class Money(cents: Long) extends AnyVal {
  def +(other: Money): Money = Money(cents + other.cents)
  def -(other: Money): Money = Money(cents - other.cents)
  def unary_- : Money = Money(-cents)
  def *(factor: Long): Money = Money(cents * factor)
  def isZero: Boolean = cents == 0
  def isNegative: Boolean = cents < 0
  def abs: Money = Money(math.abs(cents))
  override def toString: String = {
    val sign = if (cents < 0) "-" else ""
    val a = math.abs(cents)
    f"$sign$$${a / 100}.${a % 100}%02d"
  }
}

object Money {
  val Zero: Money = Money(0L)
  def fromDollars(dollars: Long, centsPart: Long = 0L): Money =
    Money(dollars * 100 + centsPart)
}

/** A quantity of shares. Whole shares only in this project. */
final case class Quantity(shares: Long) extends AnyVal {
  def +(other: Quantity): Quantity = Quantity(shares + other.shares)
  def -(other: Quantity): Quantity = Quantity(shares - other.shares)
  def unary_- : Quantity = Quantity(-shares)
  def isZero: Boolean = shares == 0
}
