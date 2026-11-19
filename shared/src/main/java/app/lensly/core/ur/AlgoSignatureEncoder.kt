package app.lensly.core.ur

import app.airvault.chains.algorand.AlgoSignResponse
import com.sparrowwallet.hummingbird.UR

/**
 * ur:algo-signature, the writing side of [AlgoSignature]. The payload is [AlgoSignResponse]'s: key 1 is the request UUID (tag 37), key 2 is the
 * 64-byte Ed25519 signature, and key 3 is the optional 32-byte public key.
 */
object AlgoSignatureEncoder {
    internal const val UR_TYPE = AlgoSignResponse.UR_TYPE
    internal const val SIGNATURE_BYTES = AlgoSignResponse.SIGNATURE_BYTES
    internal const val PUBLIC_KEY_BYTES = AlgoSignResponse.PUBLIC_KEY_BYTES

    /**
     * Uppercase frames for the response. [signature] is the 64-byte Ed25519 signature over the signed bytes. [requestId]
     * must be the 16-byte UUID from the request. [publicKey] is the optional 32-byte key. Use the first frame for a single QR
     * code.
     */
    fun encode(
        requestId: ByteArray,
        signature: ByteArray,
        publicKey: ByteArray? = null,
        maxFragment: Int = DEFAULT_MAX_FRAGMENT,
    ): Sequence<String> = framesOf(UR(UR_TYPE, AlgoSignResponse.cbor(requestId, signature, publicKey)), maxFragment)
}
