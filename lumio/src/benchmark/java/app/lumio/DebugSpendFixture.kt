package app.lumio

import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lumio.btc.BtcSpendable

/** Benchmark stub, the same as the release one: it names no coins, so the benchmark build never reads a fixture. */
internal object DebugSpendFixture {
    fun spendable(account: BtcWatchOnlyAccount): BtcSpendable? = null
}
