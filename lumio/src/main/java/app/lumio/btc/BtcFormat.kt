package app.lumio.btc

import app.lensly.core.registry.Amounts
import java.math.BigInteger

/** A satoshi amount as BTC, with trailing zeros dropped: 150000 reads "0.0015 BTC" and 0 reads "0 BTC". */
fun btcText(sats: Long): String = (if (sats < 0) "-" else "") + Amounts.format(BigInteger.valueOf(sats).abs(), BTC_DECIMALS) + " BTC"

private const val BTC_DECIMALS = 8
