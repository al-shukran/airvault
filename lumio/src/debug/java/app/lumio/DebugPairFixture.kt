package app.lumio

import android.content.Intent
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingKey
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic

/**
 * Debug-only: a Lensly pairing that the scan screen reads without a camera, so the pairing can be shown on an emulator. Launch with
 * `am start -n app.lumio/.MainActivity --ez debugHidden true --es debugPairFixture lensly`. The fixture is the wallet "Demo" of the public
 * BIP39 test mnemonic: two Ethereum accounts, one with a label and one with a group, and the Bitcoin accounts of both networks. It holds
 * no real key.
 *
 * This file lives in the debug source set only. The release stub returns null, so release builds never contain the extra's name.
 */
internal object DebugPairFixture {
    private const val EXTRA = "debugPairFixture"
    private const val UINT32_MASK = 0xFFFFFFFFL

    /** The 24-word BIP39 test vector of 256 zero bits. */
    private val WORDS = ("abandon ".repeat(23) + "art").split(" ")
    private val seed: ByteArray by lazy { Mnemonic.of(WORDS).use { it.deriveSeed("") } }

    /** The frames of the named fixture in the order a scan reads them, or null when [intent] names none. */
    fun partsFor(intent: Intent?): List<String>? = when (intent?.getStringExtra(EXTRA)) {
        "lensly" -> PairingCodec.urParts(payload())
        else -> null
    }

    private fun payload(): PairingPayload {
        val wallet = HdWallet(seed)
        val ethereum = PairingApp(
            app = "ethereum",
            keys = listOf(PairingKey.ethereumAccount(wallet.accountXpub())),
            accounts = listOf(
                PairingAccount(key = 0, child = 0, name = "DEMO 0", label = "Savings", group = null),
                PairingAccount(key = 0, child = 1, name = "DEMO 1", label = null, group = "Trading"),
            ),
        )
        val bitcoin = BtcWatchOnly.accounts(seed, BtcNetwork.MAINNET) + BtcWatchOnly.accounts(seed, BtcNetwork.TESTNET4)
        return PairingPayload(
            fingerprint = wallet.masterFingerprint.toLong() and UINT32_MASK,
            name = "Demo",
            apps = listOf(
                ethereum,
                PairingApp(
                    app = "bitcoin",
                    keys = bitcoin.map { PairingKey.bitcoinAccount(it) },
                    accounts = bitcoin.indices.map { PairingAccount(key = it, child = null, name = null, label = null, group = null) },
                ),
            ),
        )
    }
}
