package app.lensly.core.ur

import com.sparrowwallet.hummingbird.UR

/**
 * ur:algo-signature, the writing side of [AlgoSignature]. Key 1 is the request UUID (tag 37), key 2 is the 64-byte Ed25519
 * signature, and key 3 is the optional 32-byte public key.
 */
object AlgoSignatureEncoder {
    internal const val UR_TYPE = "algo-signature"
    internal const val SIGNATURE_BYTES = 64
    internal const val PUBLIC_KEY_BYTES = 32

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
    ): Sequence<String> {
        require(requestId.size == 16) { "requestId must be 16 bytes" }
        require(signature.size == SIGNATURE_BYTES) { "signature must be 64 bytes" }
        require(publicKey == null || publicKey.size == PUBLIC_KEY_BYTES) { "publicKey must be 32 bytes" }
        val fields = ArrayList<Pair<Cbor, Cbor>>()
        fields += Cbor.Uint(1L) to Cbor.Tagged(EthSignRequest.UUID_TAG, Cbor.Bytes(requestId))
        fields += Cbor.Uint(2L) to Cbor.Bytes(signature)
        if (publicKey != null) fields += Cbor.Uint(3L) to Cbor.Bytes(publicKey)
        return framesOf(UR(UR_TYPE, Cbor.encode(Cbor.Dict(fields))), maxFragment)
    }
}
