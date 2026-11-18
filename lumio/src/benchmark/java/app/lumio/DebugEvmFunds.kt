package app.lumio

import android.content.Intent
import java.math.BigInteger

/**
 * Benchmark stub, the same as the release one: it reads no extra and names no balance, so the benchmark build always
 * reads the network.
 */
internal object DebugEvmFunds {
    fun offer(intent: Intent) = Unit

    fun balance(): BigInteger? = null
}
