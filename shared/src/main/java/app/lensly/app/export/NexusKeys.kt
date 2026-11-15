package app.lensly.app.export

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.components
import app.lensly.core.ur.Cbor
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
    /** The crypto-hdkey items of this App for [seed], whose master fingerprint is [masterFingerprint]. */
    fun hdKeys(seed: ByteArray, masterFingerprint: Int): List<Cbor>
}

/**
 * The Bitcoin App: the mainnet account 0 of BIP44, BIP49, BIP84 and BIP86, in that order. Each key has children [*] at depth 0 and no
 * note, as Keystone's device writes it.
 */
object BitcoinNexusKeys : NexusKeys {
    override fun hdKeys(seed: ByteArray, masterFingerprint: Int): List<Cbor> =
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
 * The Ethereum App: the account key m/44'/60'/0' with children [0, *] and the note account.standard, then the Ledger Legacy key
 * m/44'/60'/0'/0 and the Ledger Live key m/44'/60'/0'/0/0. Keystone's device writes these three keys for one account. The two derived
 * keys carry no chain code and no children. Every key has origin depth 3, the depth of the account key.
 */
object EthereumNexusKeys : NexusKeys {
    private const val ACCOUNT_DEPTH = 3

    override fun hdKeys(seed: ByteArray, masterFingerprint: Int): List<Cbor> {
        val wallet = HdWallet(seed)
        val account = wallet.accountXpub()
        val ledgerLegacy = wallet.legacyChainXpub()
        val ledgerLive = wallet.ledgerLiveXpub()
        val accountPath = listOf(hardened(44), hardened(60), hardened(0))
        return listOf(
            nexusKey(
                publicKey = account.publicKey,
                chainCode = account.chainCode,
                origin = keypathOf(masterFingerprint, accountPath),
                children = CryptoKeypath(listOf(IndexPathComponent(0, false), WildcardPathComponent(false)), null, 0),
                parentFingerprint = account.parentFingerprint,
                note = "account.standard",
            ),
            nexusKey(
                publicKey = ledgerLegacy.publicKey,
                chainCode = null,
                origin = keypathOf(masterFingerprint, accountPath + IndexPathComponent(0, false)),
                children = null,
                parentFingerprint = ledgerLegacy.parentFingerprint,
                note = "account.ledger_legacy",
            ),
            nexusKey(
                publicKey = ledgerLive.publicKey,
                chainCode = null,
                origin = keypathOf(
                    masterFingerprint,
                    accountPath + listOf(IndexPathComponent(0, false), IndexPathComponent(0, false)),
                ),
                children = null,
                parentFingerprint = ledgerLive.parentFingerprint,
                note = "account.ledger_live",
            ),
        )
    }

    private fun hardened(childNumber: Int): PathComponent = IndexPathComponent(childNumber, true)

    private fun keypathOf(masterFingerprint: Int, steps: List<PathComponent>): CryptoKeypath =
        CryptoKeypath(steps, fingerprint(masterFingerprint), ACCOUNT_DEPTH)
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
): Cbor = MultiAccountsExport.hdKeyItem(
    CryptoHDKey(false, publicKey, chainCode, null, origin, children, fingerprint(parentFingerprint), KEY_NAME, note).toUR().cborBytes,
)

private fun fingerprint(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
