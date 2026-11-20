package app.lensly.app.export

import app.airvault.chains.algorand.algorandMainnet
import app.airvault.chains.bitcoin.bitcoinMainnet
import app.airvault.chains.bitcoin.bitcoinTestnet4
import app.airvault.chains.evm.ethereumMainnet
import app.airvault.chains.solana.SOLANA_MAINNET
import app.airvault.core.model.AddressKind
import app.airvault.core.model.ChainId
import app.airvault.core.model.Curve
import app.airvault.core.signer.AccountExport
import app.airvault.core.signer.PublicKey
import app.airvault.core.signer.SignerRef
import app.lensly.app.AccountProfile
import app.lensly.app.WalletStore
import app.lensly.app.autoAccountName
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet

/**
 * What one chain of a wallet contributes to the AirVault pairing (see [PairingCodec]): its accounts, each as the [AccountExport] of its key with
 * the name Lensly shows for it. Public data only. [paired] reads the wallet's seed, which the caller zeroes once the pairing is built.
 */
interface PairingKeys {
    /** The accounts of wallet [id] on this chain, whose master fingerprint is [fingerprint]. */
    fun paired(store: WalletStore, id: String, seed: ByteArray, fingerprint: Int): List<PairingAccount>
}

/**
 * The Ethereum accounts: one account for each account index, with the compressed public key at m/44'/60'/N'/0/0 for account N. The pairing
 * carries the key, and Lumio derives the address from it. Each account carries the name Lensly shows for it, its Auto-Name when it has none,
 * and its label and group.
 */
object EthereumPairingKeys : PairingKeys {
    override fun paired(store: WalletStore, id: String, seed: ByteArray, fingerprint: Int): List<PairingAccount> {
        val meta = requireNotNull(store.meta(id)) { "No wallet $id." }
        val wallet = HdWallet(seed)
        val profiles = store.accountProfiles(id, meta.accountCount)
        return (0 until meta.accountCount).map { account ->
            val key = AccountAddresses.addressXpub(wallet.accountXpub(account), 0)
            pairedAccount(
                export = AccountExport(
                    chain = ethereumMainnet,
                    signer = SignerRef(fingerprint, steps(44, 60, account, 0, 0)),
                    publicKey = PublicKey(Curve.SECP256K1, key.publicKey),
                    chainCode = null,
                    kind = AddressKind.DEFAULT,
                ),
                profile = profiles[account],
                autoName = autoAccountName(meta.autoNameBase, account),
            )
        }
    }
}

/**
 * The Bitcoin accounts: one watch-only extended key for each Bitcoin account the wallet stores, at m/purpose'/coin'/N' with its chain code, and
 * nothing else. A stored account is its network, its Script Type and its index, so the pairing carries the key of that Script Type and index, and
 * no other Script Type of the same index. Each account carries the name, label and group the owner gave the stored account, or its Auto-Name.
 */
object BitcoinPairingKeys : PairingKeys {
    override fun paired(store: WalletStore, id: String, seed: ByteArray, fingerprint: Int): List<PairingAccount> {
        val meta = requireNotNull(store.meta(id)) { "No wallet $id." }
        val stored = store.bitcoinAccounts(id).sortedWith(compareBy({ it.scriptType.purpose }, { it.network.coinType }, { it.index }))
        return stored.map { record ->
            val account = BtcAccount.fromSeed(seed, record.network, record.scriptType, record.index).watchOnly()
            pairedAccount(
                export = AccountExport(
                    chain = record.network.chainId(),
                    signer = SignerRef(fingerprint, account.path.path.map { step -> step.toPathStep() }),
                    publicKey = PublicKey(Curve.SECP256K1, account.xpub.publickeybytes.toByteArray()),
                    chainCode = account.xpub.chaincode.toByteArray(),
                    kind = AddressKind.DEFAULT,
                ),
                profile = record.profile,
                autoName = autoAccountName(meta.autoNameBase, record.index),
            )
        }
    }

    private fun BtcNetwork.chainId(): ChainId = when (this) {
        BtcNetwork.MAINNET -> bitcoinMainnet
        BtcNetwork.TESTNET4 -> bitcoinTestnet4
    }
}

/**
 * The Solana accounts: one Ed25519 key for each Solana account, m/44'/501'/N', and one account for each, named as Lensly shows it. The public
 * keys are the ones the wallet stores, and each is the address, so the pairing carries the 32 bytes of the key and no chain code.
 */
object SolanaPairingKeys : PairingKeys {
    override fun paired(store: WalletStore, id: String, seed: ByteArray, fingerprint: Int): List<PairingAccount> {
        val meta = requireNotNull(store.meta(id)) { "No wallet $id." }
        return store.solanaKeys(id).mapIndexed { index, publicKey ->
            pairedAccount(
                export = AccountExport(
                    chain = SOLANA_MAINNET,
                    signer = SignerRef(fingerprint, steps(44, 501, index)),
                    publicKey = PublicKey(Curve.ED25519_SLIP10, publicKey),
                    chainCode = null,
                    kind = AddressKind.DEFAULT,
                ),
                profile = null,
                autoName = autoAccountName(meta.autoNameBase, index),
            )
        }
    }
}

/**
 * The Algorand accounts: one Ed25519 key for each Algorand account, m/44'/283'/N'/0/0, and one account for each, named as Lensly shows it. The
 * public keys are the ones the wallet stores, and each is the key the address is made of, so the pairing carries the 32 bytes and no chain code.
 */
object AlgorandPairingKeys : PairingKeys {
    override fun paired(store: WalletStore, id: String, seed: ByteArray, fingerprint: Int): List<PairingAccount> {
        val meta = requireNotNull(store.meta(id)) { "No wallet $id." }
        return store.algorandKeys(id).mapIndexed { index, publicKey ->
            pairedAccount(
                export = AccountExport(
                    chain = algorandMainnet,
                    signer = SignerRef(fingerprint, steps(44, 283, index, 0, 0)),
                    publicKey = PublicKey(Curve.ED25519_BIP32, publicKey),
                    chainCode = null,
                    kind = AddressKind.DEFAULT,
                ),
                profile = null,
                autoName = autoAccountName(meta.autoNameBase, index),
            )
        }
    }
}

/** The path m/purpose'/coin'/[account]' followed by [suffix], every level as an unhardened step after the three hardened ones. */
private fun steps(purpose: Int, coin: Int, account: Int, vararg suffix: Int): List<PathStep> =
    listOf(PathStep(purpose, true), PathStep(coin, true), PathStep(account, true)) + suffix.map { PathStep(it, false) }

/** A BIP32 child number as the path step it names: the hardened bit is set on a hardened child. */
private fun Long.toPathStep(): PathStep = PathStep((this and INDEX_MASK).toInt(), this and HARDENED_BIT != 0L)

private const val HARDENED_BIT = 0x8000_0000L
private const val INDEX_MASK = 0x7FFF_FFFFL

/**
 * The pairing account of one key: its Display Name, or [autoName] when the owner gave it none, and its label and group, each cut to the room a
 * pairing gives it.
 */
private fun pairedAccount(export: AccountExport, profile: AccountProfile?, autoName: String): PairingAccount = PairingAccount(
    export = export,
    name = fitBytes(profile?.name ?: autoName, PairingCodec.MAX_NAME_BYTES),
    label = profile?.label?.let { fitBytes(it, PairingCodec.MAX_LABEL_BYTES) },
    group = profile?.group?.let { fitBytes(it, PairingCodec.MAX_LABEL_BYTES) },
)

/**
 * [text] cut to at most [maxBytes] UTF-8 bytes, at a character boundary. Every name Lensly stores is within the limit, so only a name of
 * 4-byte characters near the limit is cut.
 */
internal fun fitBytes(text: String, maxBytes: Int): String {
    var end = text.length
    while (text.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) {
        end = text.offsetByCodePoints(end, -1)
    }
    return text.substring(0, end)
}
