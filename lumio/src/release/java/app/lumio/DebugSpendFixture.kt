package app.lumio

import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lumio.btc.BtcSpendable

/** The release stub of the debug spend fixture: it names no coins, so release builds never read a fixture. */
internal object DebugSpendFixture {
    fun spendable(account: BtcWatchOnlyAccount): BtcSpendable? = null
}
