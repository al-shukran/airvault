package app.lumio

import android.content.Intent
import app.airvault.chains.algorand.algorandMainnet
import app.airvault.chains.bitcoin.bitcoinMainnet
import app.airvault.chains.bitcoin.bitcoinTestnet4
import app.airvault.chains.evm.ethereumMainnet
import app.airvault.chains.solana.SOLANA_MAINNET
import app.airvault.core.model.AddressKind
import app.airvault.core.model.Curve
import app.airvault.core.signer.AccountExport
import app.airvault.core.signer.PublicKey
import app.airvault.core.signer.SignerRef
import app.lensly.app.AddressBookEntry
import app.lensly.app.autoAccountName
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingPayload
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
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

    /** The Auto-Name base of the wallet: the Bitcoin accounts keep their Auto-Names, which the codec sends as no name. */
    private const val AUTO_NAME_BASE = "Demo"

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
        val fingerprint = wallet.masterFingerprint
        val ethereum = (0..1).map { account ->
            val key = AccountAddresses.addressXpub(wallet.accountXpub(account), 0)
            PairingAccount(
                export = AccountExport(
                    chain = ethereumMainnet,
                    signer = SignerRef(fingerprint, steps(44, 60, account, 0, 0)),
                    publicKey = PublicKey(Curve.SECP256K1, key.publicKey),
                    chainCode = null,
                    kind = AddressKind.DEFAULT,
                ),
                name = "DEMO $account",
                label = if (account == 0) "Savings" else null,
                group = if (account == 1) "Trading" else null,
            )
        }
        val bitcoin = (BtcWatchOnly.accounts(seed, BtcNetwork.MAINNET) + BtcWatchOnly.accounts(seed, BtcNetwork.TESTNET4))
            .mapIndexed { index, account ->
                PairingAccount(
                    export = account.export(fingerprint),
                    name = autoAccountName(AUTO_NAME_BASE, index),
                    label = null,
                    group = null,
                )
            }
        val solana = (0..1).map { account ->
            PairingAccount(
                export = AccountExport(
                    chain = SOLANA_MAINNET,
                    signer = SignerRef(fingerprint, steps(44, 501, account)),
                    publicKey = PublicKey(Curve.ED25519_SLIP10, SolanaAccount.publicKey(seed, account)),
                    chainCode = null,
                    kind = AddressKind.DEFAULT,
                ),
                name = "Solana $account",
                label = null,
                group = null,
            )
        }
        val algorand = (0..1).map { account ->
            PairingAccount(
                export = AccountExport(
                    chain = algorandMainnet,
                    signer = SignerRef(fingerprint, steps(44, 283, account, 0, 0)),
                    publicKey = PublicKey(Curve.ED25519_BIP32, AlgorandAccount.publicKey(seed, account)),
                    chainCode = null,
                    kind = AddressKind.DEFAULT,
                ),
                name = "Algorand $account",
                label = null,
                group = null,
            )
        }
        return PairingPayload(
            fingerprint = wallet.masterFingerprint.toLong() and UINT32_MASK,
            name = "Demo",
            autoNameBase = AUTO_NAME_BASE,
            accounts = ethereum + bitcoin + solana + algorand,
            // Public example addresses, one per chain form, so the Address Book screen has entries to show.
            addressBook = listOf(
                AddressBookEntry("5f0c2a1e-0001", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "Alice", "Savings"),
                AddressBookEntry("5f0c2a1e-0002", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", "Cold storage", null),
                AddressBookEntry("5f0c2a1e-0003", "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7", "Testnet faucet", "Testnet4"),
            ),
        )
    }

    /** The path m/purpose'/coin'/[account]' followed by [suffix], whose levels after the account are not hardened. */
    private fun steps(purpose: Int, coin: Int, account: Int, vararg suffix: Int): List<PathStep> =
        listOf(PathStep(purpose, true), PathStep(coin, true), PathStep(account, true)) + suffix.map { PathStep(it, false) }

    /** The watch-only Bitcoin account as its export: its network's chain, the key and chain code of its path, and its path. */
    private fun BtcWatchOnlyAccount.export(fingerprint: Int): AccountExport = AccountExport(
        chain = if (network == BtcNetwork.MAINNET) bitcoinMainnet else bitcoinTestnet4,
        signer = SignerRef(fingerprint, path.path.map { child -> PathStep((child and INDEX_BITS).toInt(), child and HARDENED_BIT != 0L) }),
        publicKey = PublicKey(Curve.SECP256K1, xpub.publickeybytes.toByteArray()),
        chainCode = xpub.chaincode.toByteArray(),
        kind = AddressKind.DEFAULT,
    )

    private const val HARDENED_BIT = 0x8000_0000L
    private const val INDEX_BITS = 0x7FFF_FFFFL
}
