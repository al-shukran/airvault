package app.lensly.core.ur

import com.sparrowwallet.hummingbird.UR

/**
 * ur:eth-signature response. Key 1 is the request UUID (tag 37), key 2 is r || s || v, and key 3 is the origin.
 */
object EthSignatureEncoder {
    const val DEFAULT_ORIGIN = "Lensly"

    private const val UR_TYPE = "eth-signature"
    private const val UUID_TAG = 37L
    private const val MIN_SIGNATURE_BYTES = 65
    // Legacy v (chainId * 2 + 35 + recId) is at most 8 bytes for any realistic chain ID.
    private const val MAX_SIGNATURE_BYTES = 80

    /**
     * Uppercase frames for the response. [signature] is r (32) || s (32) || v. For typed transactions v is 0 or 1,
     * for personal messages it is 27 or 28, and for legacy transactions it is the minimal big-endian EIP-155 value
     * chainId * 2 + 35 + recId. [requestId] must be the 16-byte UUID from the request. Use the first frame for a
     * single QR code.
     */
    fun encode(
        requestId: ByteArray,
        signature: ByteArray,
        origin: String = DEFAULT_ORIGIN,
        maxFragment: Int = DEFAULT_MAX_FRAGMENT,
    ): Sequence<String> {
        require(requestId.size == 16) { "requestId must be 16 bytes" }
        require(signature.size in MIN_SIGNATURE_BYTES..MAX_SIGNATURE_BYTES) { "signature must be r || s || v" }
        val payload = Cbor.Dict(
            listOf(
                Cbor.Uint(1L) to Cbor.Tagged(UUID_TAG, Cbor.Bytes(requestId)),
                Cbor.Uint(2L) to Cbor.Bytes(signature),
                Cbor.Uint(3L) to Cbor.Text(origin),
            ),
        )
        return framesOf(UR(UR_TYPE, Cbor.encode(payload)), maxFragment)
    }
}
