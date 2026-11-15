package app.lumio

import android.content.Intent
import java.math.BigInteger

/**
 * Debug-only: a funded balance for the EVM send check, so a send from a paired address with no MON on an emulator reaches its sign request,
 * its signature and its broadcast. No public testnet faucet is reachable from an emulator. The node then answers the broadcast with its own
 * insufficient-funds refusal, which is the real outcome. The balance the app shows is still read from the network; only the send check uses this.
 *
 * It is off until a launch asks for it: `adb shell am start -n app.lumio/.MainActivity --ez debugEvmFunds true`. Unit tests never ask, so they
 * keep the real check.
 *
 * This file lives in the debug source set only. The release stub does nothing, so release builds never contain the extra's name.
 */
internal object DebugEvmFunds {
    private const val EXTRA = "debugEvmFunds"

    /** One MON in wei: the balance the send check sees once a launch has asked for it. */
    private val FUNDED: BigInteger = BigInteger.TEN.pow(18)

    @Volatile private var asked = false

    /** Keeps the request [intent] carries, if it carries one. */
    fun offer(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA, false)) asked = true
    }

    /** The balance the send check uses in place of the network's, or null to read the network. */
    fun balance(): BigInteger? = if (asked) FUNDED else null
}
