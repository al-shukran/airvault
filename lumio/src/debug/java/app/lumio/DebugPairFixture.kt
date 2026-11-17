package app.lumio

import android.content.Intent
import app.lensly.app.AddressBookEntry
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingKey
import app.lensly.app.export.PairingPayload
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.wallet.Mnemonic

/**
 * Debug-only: a Lensly pairing that the scan screen reads without a camera, so the pairing can be shown on an emulator. Launch with
 * `am start -n app.lumio/.MainActivity --ez debugHidden true --es debugPairFixture lensly`. The fixture is the wallet "Demo" of the public
 * BIP39 test mnemonic: two Ethereum accounts, one with a label and one with a group, the Bitcoin accounts of both networks, and two Solana and
 * two Algorand accounts. It holds no real key.
 *
 * This file lives in the debug source set only. The release stub returns null, so release builds never contain the extra's name.
 */
internal object DebugPairFixture {
    private const val EXTRA = "debugPairFixture"
    private const val PARTS_EXTRA = "debugPairParts"
    private const val UINT32_MASK = 0xFFFFFFFFL

    /** The 24-word BIP39 test vector of 256 zero bits. */
    private val WORDS = ("abandon ".repeat(23) + "art").split(" ")
    private val seed: ByteArray by lazy { Mnemonic.of(WORDS).use { it.deriveSeed("") } }

    /**
     * The frames a scan reads, in order, or null when [intent] names none: the named fixture, or the space-separated frames of [PARTS_EXTRA].
     * The second is how frames that a debug build shows on screen reach the scan without a camera.
     */
    fun partsFor(intent: Intent?): List<String>? {
        intent?.getStringExtra(PARTS_EXTRA)?.let { parts -> return parts.trim().split(' ').filter { it.isNotEmpty() } }
        return when (intent?.getStringExtra(EXTRA)) {
            "lensly" -> PairingCodec.urParts(payload(), brotli = null)
            else -> null
        }
    }

    private fun payload(): PairingPayload {
        val wallet = HdWallet(seed)
        val ethereum = PairingApp(
            app = "ethereum",
            keys = (0..1).map { account ->
                PairingKey.ethereumAddress(account, AccountAddresses.addressXpub(wallet.accountXpub(account), 0))
            },
            accounts = listOf(
                PairingAccount(key = 0, name = "DEMO 0", label = "Savings", group = null),
                PairingAccount(key = 1, name = "DEMO 1", label = null, group = "Trading"),
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
                    accounts = bitcoin.indices.map { PairingAccount(key = it, name = null, label = null, group = null) },
                ),
                PairingApp(
                    app = "solana",
                    keys = (0..1).map { account ->
                        PairingKey.solanaAccount(account, SolanaAccount.publicKey(seed, account), SolanaAccount.chainCode(seed, account))
                    },
                    accounts = (0..1).map { PairingAccount(key = it, name = "Solana $it", label = null, group = null) },
                ),
                PairingApp(
                    app = "algorand",
                    keys = (0..1).map { account ->
                        PairingKey.algorandAccount(account, AlgorandAccount.publicKey(seed, account), AlgorandAccount.chainCode(seed, account))
                    },
                    accounts = (0..1).map { PairingAccount(key = it, name = "Algorand $it", label = null, group = null) },
                ),
            ),
            // Public example addresses, one per chain form, so the Address Book screen has entries to show.
            addressBook = listOf(
                AddressBookEntry("5f0c2a1e-0001", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "Alice", "Savings"),
                AddressBookEntry("5f0c2a1e-0002", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", "Cold storage", null),
                AddressBookEntry("5f0c2a1e-0003", "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7", "Testnet faucet", "Testnet4"),
            ),
        )
    }
}
