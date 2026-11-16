package app.lensly.core.ur

import com.sparrowwallet.hummingbird.UR

/**
 * ur:sol-signature, the writing side of the Keystone sol-signature type. Key 1 is the request UUID (tag 37) and key 2 is the
 * 64-byte Ed25519 signature. Like Keystone's, it carries no origin.
 */
object SolSignatureEncoder {
    internal const val UR_TYPE = "sol-signature"
    private const val UUID_TAG = 37L
    internal const val SIGNATURE_BYTES = 64

    /**
     * Uppercase frames for the response. [signature] is the 64-byte Ed25519 signature over the message bytes that the
     * request carried. [requestId] must be the 16-byte UUID from the request. Use the first frame for a single QR code.
     */
    fun encode(
        requestId: ByteArray,
        signature: ByteArray,
        maxFragment: Int = DEFAULT_MAX_FRAGMENT,
    ): Sequence<String> {
        require(requestId.size == 16) { "requestId must be 16 bytes" }
        require(signature.size == SIGNATURE_BYTES) { "signature must be 64 bytes" }
        val payload = Cbor.Dict(
            listOf(
                Cbor.Uint(1L) to Cbor.Tagged(UUID_TAG, Cbor.Bytes(requestId)),
                Cbor.Uint(2L) to Cbor.Bytes(signature),
            ),
        )
        return framesOf(UR(UR_TYPE, Cbor.encode(payload)), maxFragment)
    }
}
