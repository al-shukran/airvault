package app.lensly.core.ur

import app.lensly.core.wallet.AccountXpub
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import java.nio.ByteBuffer

/** The refusal for any key that is not a public key: a private key, or a key a watch-only copy must not hold. */
const val PRIVATE_KEY_REFUSAL = "The code holds private key material. Only public exports can be paired."

/**
 * The account key that a ur:crypto-hdkey carries: the public key and chain code of m/44'/60'/0', its parent fingerprint, the master
 * fingerprint of the seed, and the name the exporter gave it. Public data only.
 */
data class HdKeyAccount(
    val xpub: AccountXpub,
    /** The master fingerprint of the seed, as the signed 32-bit value that [app.lensly.core.wallet.HdWallet] holds. */
    val masterFingerprint: Int,
    /** The exporter's name for the wallet, or null when the code carries none. */
    val name: String?,
)

/**
 * Reads the account key that [CryptoHdKeyEncoder] writes. It takes only the Ethereum account key m/44'/60'/0' as public data. A private
 * key, a key on any other path, or a key with no origin is refused, so the caller can show the owner why.
 */
object CryptoHdKeyDecoder {
    private const val PUBLIC_KEY_BYTES = 33
    private const val CHAIN_CODE_BYTES = 32
    private const val PRIVATE_KEY_BYTES = 32
    private const val FINGERPRINT_BYTES = 4
    private const val ACCOUNT_DEPTH = 3
    private val ACCOUNT_PATH: List<Pair<Int, Boolean>> = listOf(44 to true, 60 to true, 0 to true)

    /**
     * The account key in [ur], which must be a ur:crypto-hdkey.
     * @throws IllegalArgumentException with a message the owner can read, when [ur] is not a public Ethereum account key.
     */
    fun accountKey(ur: UR): HdKeyAccount {
        val hdKey = try {
            ur.decodeFromRegistry() as? CryptoHDKey
        } catch (e: Exception) {
            throw IllegalArgumentException("not a ur:crypto-hdkey", e)
        } ?: throw IllegalArgumentException("not a ur:crypto-hdkey")
        if (hdKey.isPrivateKey || hdKey.key.size == PRIVATE_KEY_BYTES) throw IllegalArgumentException(PRIVATE_KEY_REFUSAL)
        require(hdKey.key.size == PUBLIC_KEY_BYTES) { "The code's public key is not 33 bytes." }
        require(hdKey.chainCode?.size == CHAIN_CODE_BYTES) { "The code's chain code is not 32 bytes." }
        val origin = hdKey.origin ?: throw IllegalArgumentException(NOT_ACCOUNT_KEY)
        val path = origin.components.map { component ->
            val index = component as? IndexPathComponent ?: throw IllegalArgumentException(NOT_ACCOUNT_KEY)
            index.index to index.isHardened
        }
        if (path != ACCOUNT_PATH || (origin.depth != null && origin.depth != ACCOUNT_DEPTH)) {
            throw IllegalArgumentException(NOT_ACCOUNT_KEY)
        }
        val source = origin.sourceFingerprint
        require(source?.size == FINGERPRINT_BYTES) { "The code has no master fingerprint." }
        val parent = hdKey.parentFingerprint
        require(parent?.size == FINGERPRINT_BYTES) { "The code has no parent fingerprint." }
        return HdKeyAccount(
            xpub = AccountXpub(
                publicKey = hdKey.key,
                chainCode = hdKey.chainCode,
                parentFingerprint = ByteBuffer.wrap(parent).int,
            ),
            masterFingerprint = ByteBuffer.wrap(source).int,
            name = hdKey.name,
        )
    }

    private const val NOT_ACCOUNT_KEY = "The code is not the Ethereum account key m/44'/60'/0'."
}
