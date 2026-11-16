package app.lensly.app.export

import app.lensly.core.ur.Cbor
import app.lensly.core.wallet.AccountXpub
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoKeypath
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import com.sparrowwallet.hummingbird.registry.pathcomponent.PathComponent
import java.nio.ByteBuffer

/**
 * The Ethereum App: one Ledger Live key for each account, m/44'/60'/N' for account N, with the children 0/0 that reach its address
 * m/44'/60'/N'/0/0 and the note account.ledger_live. Every key has origin depth 3, the depth of the account key.
 *
 * The standard key m/44'/60'/0' with children [0, *] and the Ledger Legacy key are not written. Their children are not Lensly's
 * accounts: Lensly's account N is m/44'/60'/N'/0/0, and the old form, with N as the last step, is not one of its keys.
 */
object EthereumNexusKeys {
    private const val ACCOUNT_DEPTH = 3

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
