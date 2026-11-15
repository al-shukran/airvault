package app.lumio

import android.content.Intent
import java.math.BigInteger

/** The release stub of the debug EVM funds: it reads no extra and names no balance, so release builds always read the network. */
internal object DebugEvmFunds {
    fun offer(intent: Intent) = Unit

    fun balance(): BigInteger? = null
}
