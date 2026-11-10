package app.lensly.core.ur

import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoKeypath
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import com.sparrowwallet.hummingbird.registry.pathcomponent.PathComponent
import java.nio.ByteBuffer

/**
 * ur:crypto-hdkey (BCR-2020-007) for the Lensly account key m/44'/60'/0'.
 *
 * The map carries key 3 (compressed public key), key 4 (chain code), key 6 (origin m/44'/60'/0' with the
 * master fingerprint), key 8 (parent fingerprint of m/44'/60') and key 9 (name). Hummingbird's
 * [CryptoHDKey] writes the same keys in the same order as the Keystone reference vectors.
 */
object CryptoHdKeyEncoder {

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
    ): Sequence<String> = framesOf(ur(pubkey33, chainCode32, masterFp, parentFp, name), maxFragment)

    /** [name] may be null, which omits key 9. That is only used to compare against the Keystone vectors. */
    internal fun ur(
        pubkey33: ByteArray,
        chainCode32: ByteArray,
        masterFp: Int,
        parentFp: Int,
        name: String?,
    ): UR {
        require(pubkey33.size == 33) { "compressed public key must be 33 bytes" }
        require(chainCode32.size == 32) { "chain code must be 32 bytes" }
        val path: List<PathComponent> = listOf(
            IndexPathComponent(44, true),
            IndexPathComponent(60, true),
            IndexPathComponent(0, true),
        )
        val origin = CryptoKeypath(path, fingerprintBytes(masterFp))
        // isMaster and isPrivate stay null, so keys 1 and 2 are absent. Children and note are absent too.
        val hdKey = CryptoHDKey(
            null,
            pubkey33,
            chainCode32,
            null,
            origin,
            null,
            fingerprintBytes(parentFp),
            name,
            null,
        )
        return hdKey.toUR()
    }

    private fun fingerprintBytes(fingerprint: Int): ByteArray = ByteBuffer.allocate(4).putInt(fingerprint).array()
}
