package app.lumio.btc

import app.lensly.core.registry.Amounts
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A satoshi amount as BTC, with trailing zeros dropped: 150000 reads "0.0015 BTC" and 0 reads "0 BTC". */
fun btcText(sats: Long): String = (if (sats < 0) "-" else "") + Amounts.format(BigInteger.valueOf(sats).abs(), BTC_DECIMALS) + " BTC"

/** A signed change in satoshis: "+0.0015 BTC" for a gain, "-0.0015 BTC" for a loss, and "0 BTC" for none. */
fun signedBtcText(sats: Long): String = if (sats > 0) "+" + btcText(sats) else btcText(sats)

/** When a scan was taken, in the device's time zone, such as "as of 15 Nov 2026, 10:32". */
fun asOfText(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault()).format(Instant.ofEpochMilli(epochMs).atZone(zone))
    return "as of $time"
}

/** When a balance was last updated, in the device's time zone, such as "Updated 15 Nov 2026, 10:32". */
fun updatedText(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = asOfText(epochMs, zone).replaceFirst("as of", "Updated")

/** How many confirmations a transaction has, or that it is unconfirmed. */
fun confirmationsText(confirmations: Long): String = when (confirmations) {
    0L -> "Unconfirmed"
    1L -> "1 confirmation"
    else -> "$confirmations confirmations"
}

/** A transaction id shortened for a row: its first ten and last six characters. */
fun shortTxid(txid: String): String = if (txid.length <= SHORT_TXID_MIN) txid else txid.take(10) + "..." + txid.takeLast(6)

private const val BTC_DECIMALS = 8
private const val SHORT_TXID_MIN = 20
