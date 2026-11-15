package app.lumio.evm

import app.lensly.core.registry.Amounts
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The text Lumio shows for a native amount and for the time of a read. */
object EvmFormat {
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault())

    /** A native amount with its symbol, such as "0.5 ETH". */
    fun amount(wei: BigInteger, decimals: Int, symbol: String): String = "${Amounts.format(wei, decimals)} $symbol"

    /** The local time of [epochMs], such as "15 Nov 09:30". */
    fun asOf(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(STAMP)
}
