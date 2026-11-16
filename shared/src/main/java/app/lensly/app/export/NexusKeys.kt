package app.lensly.app.export

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.components
import app.lensly.core.ur.Cbor
import app.lensly.core.wallet.AccountXpub
import app.lensly.core.wallet.HdWallet
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoKeypath
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import com.sparrowwallet.hummingbird.registry.pathcomponent.PathComponent
import com.sparrowwallet.hummingbird.registry.pathcomponent.WildcardPathComponent
import java.nio.ByteBuffer

/**
 * The public account keys that one App of a wallet contributes to the Keystone Nexus export, as crypto-hdkey items (see
 * [MultiAccountsExport]). An App states its keys by implementing this, and `WalletApp` lists the implementation, so the export carries
 * the keys of every App in that list, in its order. The keys are derived from the seed and carry public data only.
 */
interface NexusKeys {
    /** The crypto-hdkey items of this App for [seed], whose master fingerprint is [masterFingerprint], for a wallet of [accountCount] accounts. */
    fun hdKeys(seed: ByteArray, masterFingerprint: Int, accountCount: Int): List<Cbor>
}

/**
 * The Bitcoin App: the mainnet account 0 of BIP44, BIP49, BIP84 and BIP86, in that order. Each key has children [*] at depth 0 and no
 * note, as Keystone's device writes it.
 */
object BitcoinNexusKeys : NexusKeys {
    override fun hdKeys(seed: ByteArray, masterFingerprint: Int, accountCount: Int): List<Cbor> =
        BtcWatchOnly.accounts(seed, BtcNetwork.MAINNET, accountIndex = 0).map { account ->
            nexusKey(
                publicKey = account.xpub.publickeybytes.toByteArray(),
                chainCode = account.xpub.chaincode.toByteArray(),
                origin = CryptoKeypath(account.path.components(), fingerprint(masterFingerprint), account.path.path.size),
                children = CryptoKeypath(listOf(WildcardPathComponent(false)), null, 0),
                parentFingerprint = account.xpub.parent.toInt(),
                note = null,
            )
        }
}

/**
 * The Ethereum App: one Ledger Live key for each account, m/44'/60'/N' for account N, with the children 0/0 that reach its address
 * m/44'/60'/N'/0/0 and the note account.ledger_live. Every key has origin depth 3, the depth of the account key.
 *
 * The standard key m/44'/60'/0' with children [0, *] and the Ledger Legacy key are not written. Their children are not Lensly's
 * accounts: Lensly's account N is m/44'/60'/N'/0/0, and the old form, with N as the last step, is not one of its keys.
 */
object EthereumNexusKeys : NexusKeys {
    private const val ACCOUNT_DEPTH = 3

    override fun hdKeys(seed: ByteArray, masterFingerprint: Int, accountCount: Int): List<Cbor> {
        val wallet = HdWallet(seed)
        return ledgerLiveKeys((0 until accountCount).map { wallet.accountXpub(it) }, masterFingerprint, KEY_NAME)
    }

    /**
     * The Ledger Live keys of [accountKeys], the account keys m/44'/60'/N' in account order, each named [name]. The keys are public data, so
     * the keys a wallet stored when it was made are the keys its seed gives. The MetaMask and Rabby export uses this encoding too.
     */
    fun ledgerLiveKeys(accountKeys: List<AccountXpub>, masterFingerprint: Int, name: String): List<Cbor> =
        accountKeys.mapIndexed { account, key ->
            nexusKey(
                publicKey = key.publicKey,
                chainCode = key.chainCode,
                origin = keypathOf(masterFingerprint, listOf(hardened(44), hardened(60), hardened(account))),
                children = CryptoKeypath(listOf(IndexPathComponent(0, false), IndexPathComponent(0, false)), null, 0),
                parentFingerprint = key.parentFingerprint,
                note = "account.ledger_live",
                name = name,
            )
        }

    private fun hardened(childNumber: Int): PathComponent = IndexPathComponent(childNumber, true)

    private fun keypathOf(masterFingerprint: Int, steps: List<PathComponent>): CryptoKeypath =
        CryptoKeypath(steps, fingerprint(masterFingerprint), ACCOUNT_DEPTH)
}

/**
 * The Solana App: no key yet. The Keystone Nexus export of Solana accounts waits for WI-21, which settles the key encoding Keystone reads,
 * so the export carries no Solana key until then.
 */
object SolanaNexusKeys : NexusKeys {
    override fun hdKeys(seed: ByteArray, masterFingerprint: Int, accountCount: Int): List<Cbor> = emptyList()
}

/** The name every key carries. It is Lensly's own name, the same as the device label of the export. */
private const val KEY_NAME = "Lensly"

/** One account key as a crypto-hdkey item: is_private false, the key and chain code, origin and children, parent, name and note. */
private fun nexusKey(
    publicKey: ByteArray,
    chainCode: ByteArray?,
    origin: CryptoKeypath,
    children: CryptoKeypath?,
    parentFingerprint: Int,
    note: String?,
    name: String = KEY_NAME,
): Cbor = MultiAccountsExport.hdKeyItem(
    CryptoHDKey(false, publicKey, chainCode, null, origin, children, fingerprint(parentFingerprint), name, note).toUR().cborBytes,
)

private fun fingerprint(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
