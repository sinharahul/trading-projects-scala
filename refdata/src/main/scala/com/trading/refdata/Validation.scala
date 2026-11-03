package com.trading.refdata

object Validation {

  private def cusipCharValue(c: Char): Option[Int] = c match {
    case d if d.isDigit => Some(d.asDigit)
    case l if l >= 'A' && l <= 'Z' => Some(l - 'A' + 10)
    case '*' => Some(36)
    case '@' => Some(37)
    case '#' => Some(38)
    case _ => None
  }

  /** True when the 9-character CUSIP has a correct check digit. */
  def isValidCusip(cusip: String): Boolean = {
    val c = cusip.trim.toUpperCase
    if (c.length != 9) return false
    val values = c.take(8).flatMap(cusipCharValue)
    if (values.length != 8) return false
    val sum = values.zipWithIndex.map { case (v, i) =>
      val doubled = if ((i + 1) % 2 == 0) v * 2 else v
      doubled / 10 + doubled % 10
    }.sum
    val expected = (10 - (sum % 10)) % 10
    c(8).isDigit && c(8).asDigit == expected
  }

  private def isinToDigits(isin: String): Option[String] = {
    val sb = new StringBuilder
    for (ch <- isin.trim.toUpperCase) {
      if (ch.isDigit) sb.append(ch)
      else if (ch >= 'A' && ch <= 'Z') sb.append(ch - 'A' + 10)
      else return None
    }
    Some(sb.toString)
  }

  /** True when the 12-character ISIN has a correct Luhn check digit. */
  def isValidIsin(isin: String): Boolean = {
    val s = isin.trim.toUpperCase
    if (s.length != 12) return false
    isinToDigits(s) match {
      case None => false
      case Some(digits) =>
        val sum = digits.reverse.zipWithIndex.map { case (ch, i) =>
          val d = ch.asDigit
          if (i % 2 == 1) {
            val doubled = d * 2
            doubled / 10 + doubled % 10
          } else d
        }.sum
        sum % 10 == 0
    }
  }

  /** A SEDOL is 7 characters; the check digit uses weights 1,3,1,7,3,9. */
  def isValidSedol(sedol: String): Boolean = {
    val s = sedol.trim.toUpperCase
    if (s.length != 7) return false
    val weights = Seq(1, 3, 1, 7, 3, 9)
    val values = s.take(6).flatMap(cusipCharValue)
    if (values.length != 6) return false
    val sum = values.zip(weights).map { case (v, w) => v * w }.sum
    val expected = (10 - (sum % 10)) % 10
    s(6).isDigit && s(6).asDigit == expected
  }

  def isValidCurrency(code: String): Boolean =
    code.trim.length == 3 && code.trim.forall(_.isLetter) &&
      code.trim.toUpperCase == code.trim

  def isValidMic(mic: String): Boolean =
    mic.trim.length == 4 && mic.trim.forall(c => c.isLetterOrDigit)

  /** An LEI is 20 characters: 4-char prefix, 2-char zero, 12-char
    * entity code, 2 check digits (ISO 17442 mod-97). We validate
    * structure here; full mod-97 validation is an exercise.
    */
  def isValidLeiStructure(lei: String): Boolean = {
    val s = lei.trim.toUpperCase
    s.length == 20 && s.forall(_.isLetterOrDigit) && s.substring(4, 6) == "00"
  }
}
