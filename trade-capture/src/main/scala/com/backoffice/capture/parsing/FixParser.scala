package com.backoffice.capture.parsing

import com.backoffice.capture.model._
import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.util.Try

object FixParser {

  private val FieldSeparator = '|'
  private val FixTimestampFormat =
    DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")

  /**
   * Parse one pipe-delimited execution report.
   * Returns Right(RawExecution) on success, Left(reason) on any failure.
   * The caller routes Left values to the dead-letter queue; the parser
   * itself never throws and never drops a message silently.
   */
  def parse(raw: String): Either[String, RawExecution] = {
    val fields: Map[String, String] = raw
      .split(FieldSeparator)
      .filter(_.contains("="))
      .map { kv =>
        val idx = kv.indexOf('=')
        kv.substring(0, idx) -> kv.substring(idx + 1)
      }
      .toMap

    for {
      _          <- requireTag(fields, "35").filterOrElse(_ == "8",
                      "Not an ExecutionReport (35 != 8)")
      execId     <- requireTag(fields, "17")
      orderId    <- requireTag(fields, "11")
      symbol     <- requireTag(fields, "55")
      side       <- requireTag(fields, "54").flatMap(Side.fromFix)
      execType   <- requireTag(fields, "150").flatMap(ExecType.fromFix)
      orderQty   <- requireTag(fields, "38").flatMap(parseLong("38"))
      lastQty    <- requireTag(fields, "32").flatMap(parseLong("32"))
      lastPx     <- requireTag(fields, "31").flatMap(parseBigDecimal("31"))
      transact   <- requireTag(fields, "60").flatMap(parseTimestamp("60"))
      sender     <- requireTag(fields, "49")
      seqNum     <- requireTag(fields, "34").flatMap(parseLong("34"))
      _          <- verifyChecksum(raw, fields.get("10"))
    } yield RawExecution(
      execId = execId,
      orderId = orderId,
      symbol = symbol,
      side = side,
      execType = execType,
      orderQty = orderQty,
      lastQty = lastQty,
      lastPx = lastPx,
      transactTime = transact,
      senderCompId = sender,
      msgSeqNum = seqNum
    )
  }

  private def requireTag(fields: Map[String, String], tag: String): Either[String, String] =
    fields.get(tag) match {
      case Some(v) if v.trim.nonEmpty => Right(v.trim)
      case _ => Left(s"Missing or blank required tag: $tag")
    }

  private def parseLong(tag: String)(s: String): Either[String, Long] =
    Try(s.toLong).toEither.left.map(_ => s"Tag $tag is not a valid integer: $s")

  private def parseBigDecimal(tag: String)(s: String): Either[String, BigDecimal] =
    Try(BigDecimal(s)).toEither.left.map(_ => s"Tag $tag is not a valid decimal: $s")

  private def parseTimestamp(tag: String)(s: String): Either[String, Instant] =
    Try {
      val local = java.time.LocalDateTime.parse(s, FixTimestampFormat)
      local.toInstant(java.time.ZoneOffset.UTC)
    }.toEither.left.map(_ => s"Tag $tag is not a valid FIX timestamp: $s")

  /**
   * FIX checksum: sum of all bytes up to (not including) the checksum
   * field itself, modulo 256, formatted as a three-digit string.
   * This implementation computes it over the pipe-delimited form, which
   * matches the sample messages used in this project. A production
   * parser would compute it over SOH-delimited bytes.
   */
  private def verifyChecksum(raw: String, claimed: Option[String]): Either[String, Unit] =
    claimed match {
      case None => Left("Missing checksum tag: 10")
      case Some(c) =>
        val body = raw.substring(0, raw.lastIndexOf("|10="))
        val sum = body.getBytes("US-ASCII").map(b => b & 0xff).sum % 256
        val expected = f"$sum%03d"
        if (expected == c) Right(())
        else Left(s"Checksum mismatch: claimed $c, computed $expected")
    }

  // Small helper: Either.filterOrElse is not in the standard library
  // for all versions, so define the intent explicitly.
  implicit class EitherOps[E, A](e: Either[E, A]) {
    def filterOrElse(p: A => Boolean, error: => E): Either[E, A] =
      e.flatMap(a => if (p(a)) Right(a) else Left(error))
  }
}
