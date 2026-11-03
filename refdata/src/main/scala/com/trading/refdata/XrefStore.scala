package com.trading.refdata

import java.sql.{Connection, DriverManager}
import java.time.LocalDate

object XrefStore {
  private val url = "jdbc:postgresql://localhost:5432/trading"
  private val user = "trading"
  private val password = "trading"

  def connection(): Connection = {
    Class.forName("org.postgresql.Driver")
    DriverManager.getConnection(url, user, password)
  }

  /** Creates the cross-reference table if it does not exist. Run
    * this once during setup, not on every load.
    */
  def init(): Unit = {
    val conn = connection()
    try {
      val stmt = conn.createStatement()
      stmt.execute(
        """CREATE TABLE IF NOT EXISTS refdata_xref (
          |  id_type    VARCHAR(20) NOT NULL,
          |  id_value   VARCHAR(40) NOT NULL,
          |  golden_key VARCHAR(36) NOT NULL,
          |  source     VARCHAR(20) NOT NULL,
          |  valid_from DATE NOT NULL,
          |  valid_to   DATE,
          |  PRIMARY KEY (id_type, id_value, valid_from)
          |)""".stripMargin)
      stmt.execute(
        "CREATE INDEX IF NOT EXISTS idx_xref_golden ON refdata_xref (golden_key)")
      stmt.execute(
        "CREATE INDEX IF NOT EXISTS idx_xref_value ON refdata_xref (id_type, id_value)")
      stmt.close()
    } finally conn.close()
  }

  /** Golden keys that an identifier has ever denoted, newest first.
    * The caller decides whether a returned key is currently valid;
    * this function reports history, because history is what the
    * conflict check needs to distinguish reuse from identity.
    */
  def lookup(idType: IdentifierType, value: String): Seq[String] = {
    val conn = connection()
    try {
      val ps = conn.prepareStatement(
        """SELECT golden_key FROM refdata_xref
          | WHERE id_type = ? AND id_value = ?
          | ORDER BY valid_from DESC""".stripMargin)
      ps.setString(1, idType.toString)
      ps.setString(2, value)
      val rs = ps.executeQuery()
      val keys = scala.collection.mutable.ArrayBuffer.empty[String]
      while (rs.next()) keys += rs.getString(1)
      rs.close()
      ps.close()
      keys.toSeq
    } finally conn.close()
  }

  /** Records a new identifier-to-golden-key link. If the same
    * identifier already points at a different golden key with an
    * open validity period, the old row is closed first: that is
    * how a correction to a mapping is recorded without deleting
    * the history of the old mapping.
    */
  def link(idType: IdentifierType, value: String, goldenKey: String,
           source: String, validFrom: LocalDate): Unit = {
    val conn = connection()
    try {
      val close = conn.prepareStatement(
        """UPDATE refdata_xref SET valid_to = ?
          | WHERE id_type = ? AND id_value = ?
          | AND valid_to IS NULL AND golden_key <> ?""".stripMargin)
      close.setDate(1, java.sql.Date.valueOf(validFrom))
      close.setString(2, idType.toString)
      close.setString(3, value)
      close.setString(4, goldenKey)
      close.executeUpdate()
      close.close()
      val insert = conn.prepareStatement(
        """INSERT INTO refdata_xref
          | (id_type, id_value, golden_key, source, valid_from, valid_to)
          | VALUES (?, ?, ?, ?, ?, NULL)
          | ON CONFLICT (id_type, id_value, valid_from) DO NOTHING""".stripMargin)
      insert.setString(1, idType.toString)
      insert.setString(2, value)
      insert.setString(3, goldenKey)
      insert.setString(4, source)
      insert.setDate(5, java.sql.Date.valueOf(validFrom))
      insert.executeUpdate()
      insert.close()
    } finally conn.close()
  }

  /** Writes all of a survived security's identifiers into the
    * cross-reference table after a successful load.
    */
  def recordGolden(g: GoldenSecurity, validFrom: LocalDate): Unit = {
    val ids = Seq(
      g.cusip.value.map(IdentifierType.Cusip -> _),
      g.isin.value.map(IdentifierType.Isin -> _),
      g.sedol.value.map(IdentifierType.Sedol -> _),
      g.figi.value.map(IdentifierType.Figi -> _)
    ).flatten
    for ((idType, value) <- ids) {
      link(idType, value, g.goldenKey, g.cusip.winningSource, validFrom)
    }
  }
}
