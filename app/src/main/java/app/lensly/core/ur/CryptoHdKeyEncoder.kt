package app.lensly.core.ur

import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoKeypath
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import com.sparrowwallet.hummingbird.registry.pathcomponent.PathComponent
import java.nio.ByteBuffer

/**
 * ur:crypto-hdkey (BCR-2020-007) for a public key on an Ethereum path.
 *
 * The default is the Lensly account key m/44'/60'/0', whose map carries key 3 (compressed public key), key 4 (chain code), key 6
 * (origin with the master fingerprint), key 8 (parent fingerprint of m/44'/60') and key 9 (name). Hummingbird's [CryptoHDKey]
 * writes the same keys in the same order as the Keystone reference vectors. An address key passes its own [origin] and [depth],
 * which add the depth to the origin.
 */
object CryptoHdKeyEncoder {

    /** The account key's origin, m/44'/60'/0', as BIP32 child numbers. A hardened step has the 2^31 bit set. */
    val ACCOUNT_ORIGIN: List<Int> = listOf(hardened(44), hardened(60), hardened(0))

    /** The complete UR as one uppercase string. Use [urParts] when it may not fit a single QR code. */
    fun encode(pubkey33: ByteArray, chainCode32: ByteArray, masterFp: Int, parentFp: Int, name: String): String =
        UREncoder.encode(ur(pubkey33, chainCode32, masterFp, parentFp, name)).uppercase()

    /**
     * Uppercase frames. A payload that fits in [maxFragment] bytes is one frame, repeated. A larger payload
     * is an endless fountain stream for animated QR.
     */
    fun urParts(
        pubkey33: ByteArray,
        chainCode32: ByteArray,
        masterFp: Int,
        parentFp: Int,
        name: String,
        maxFragment: Int = DEFAULT_MAX_FRAGMENT,
        origin: List<Int> = ACCOUNT_ORIGIN,
        depth: Int? = null,
    ): Sequence<String> = framesOf(ur(pubkey33, chainCode32, masterFp, parentFp, name, origin, depth), maxFragment)

    /**
     * [name] may be null, which omits key 9. That is only used to compare against the Keystone vectors. [origin] lists the BIP32
     * child numbers of the key's path, and [depth] is written into the origin when it is not null.
     */
    internal fun ur(
        pubkey33: ByteArray,
        chainCode32: ByteArray,
        masterFp: Int,
        parentFp: Int,
        name: String?,
        origin: List<Int> = ACCOUNT_ORIGIN,
        depth: Int? = null,
    ): UR {
        require(pubkey33.size == 33) { "compressed public key must be 33 bytes" }
        require(chainCode32.size == 32) { "chain code must be 32 bytes" }
        val path: List<PathComponent> = origin.map { child ->
            IndexPathComponent(child and HARDENED_BIT.inv(), child < 0)
        }
        val keypath = CryptoKeypath(path, fingerprintBytes(masterFp), depth)
        // isMaster and isPrivate stay null, so keys 1 and 2 are absent. Children and note are absent too.
        val hdKey = CryptoHDKey(
            null,
            pubkey33,
            chainCode32,
            null,
            keypath,
            null,
            fingerprintBytes(parentFp),
            name,
            null,
        )
        return hdKey.toUR()
    }

    private fun hardened(childNumber: Int): Int = childNumber or HARDENED_BIT

    private const val HARDENED_BIT = Int.MIN_VALUE

    private fun fingerprintBytes(fingerprint: Int): ByteArray = ByteBuffer.allocate(4).putInt(fingerprint).array()
}
