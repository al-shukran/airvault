package app.lumio.data.provider.algorand

import app.lensly.core.registry.Amounts
import java.math.BigInteger

/** The text Lumio shows for an Algorand amount, such as "1.5 ALGO". */
object AlgorandFormat {
    /** One ALGO is 10^6 microAlgos, the base unit of an account's `amount`. */
    private const val DECIMALS = 6

    /** A native amount in microAlgos with its symbol: 1500000 is "1.5 ALGO", and 0 is "0 ALGO". */
    fun amount(microAlgos: BigInteger): String = "${Amounts.format(microAlgos, DECIMALS)} ALGO"
}
