package com.backoffice.eod.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object SurrogateKeys {
  /** Stable 63-bit positive key from a business key string.
    * Deterministic across runs, which is required for rerun
    * reproducibility (Step 8). Not collision-proof in theory;
    * the loader checks for collisions and fails loudly if one occurs.
    */
  def keyFor(businessKey: String): Long = {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(businessKey.getBytes(StandardCharsets.UTF_8))
    var value = 0L
    var i = 0
    while (i < 8) {
      value = (value << 8) | (digest(i) & 0xffL)
      i += 1
    }
    value & Long.MaxValue
  }

  def securityKey(ticker: String): Long = keyFor(s"SEC:$ticker")
  def accountKey(accountNumber: String): Long = keyFor(s"ACC:$accountNumber")
  def tradeKey(tradeId: String): Long = keyFor(s"TRD:$tradeId")
}
