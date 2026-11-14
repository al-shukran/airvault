package app.lumio

import android.content.Intent
import app.lensly.app.export.PairingExport
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic

/**
 * Debug-only: a Lensly pairing export that the scan screen reads without a camera, so the pairing flow can be shown on an emulator.
 * Launch with `am start -n app.lumio/.MainActivity --ez debugHidden true --es debugPairFixture <name>`, where the name is `evm`
 * (the account key) or `btc` (both networks, four Script Types, animated). The fixture is derived from the public BIP39 test mnemonic,
 * so it holds no real key.
 *
 * This file lives in the debug source set only. The release stub returns null, so release builds never contain the extra's name.
 */
internal object DebugPairFixture {
    private const val EXTRA = "debugPairFixture"
    /** The 24-word BIP39 test vector of 256 zero bits. */
    private val WORDS = ("abandon ".repeat(23) + "art").split(" ")
    private val seed: ByteArray by lazy { Mnemonic.of(WORDS).use { it.deriveSeed("") } }

    /** The frames of the named fixture in the order a scan reads them, or null when [intent] names none. */
    fun partsFor(intent: Intent?): List<String>? = when (intent?.getStringExtra(EXTRA)) {
        "evm" -> PairingExport.urParts(HdWallet(seed))
        "btc" -> BtcWatchOnly.cryptoAccountParts(
            BtcWatchOnly.accounts(seed, BtcNetwork.MAINNET) + BtcWatchOnly.accounts(seed, BtcNetwork.TESTNET4),
        )
        else -> null
    }
}
