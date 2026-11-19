package app.airvault.chains.algorand

import app.lensly.core.ur.Cbor
import app.lensly.core.ur.UUID_TAG

/**
 * The `algo-signature` answer to an algo-sign-request. Key 1 is the request UUID (tag 37), key 2 the 64-byte Ed25519 signature, and key 3 the
 * optional 32-byte public key of the signing account. The core frames the payload as a UR.
 */
object AlgoSignResponse {
    const val UR_TYPE = "algo-signature"
    const val SIGNATURE_BYTES = 64
    const val PUBLIC_KEY_BYTES = 32
    private const val REQUEST_ID_BYTES = 16

    /** The CBOR payload that answers request [requestId] with [signature], and with [publicKey] when given. */
    fun cbor(requestId: ByteArray, signature: ByteArray, publicKey: ByteArray? = null): ByteArray {
        require(requestId.size == REQUEST_ID_BYTES) { "requestId must be 16 bytes" }
        require(signature.size == SIGNATURE_BYTES) { "signature must be 64 bytes" }
        require(publicKey == null || publicKey.size == PUBLIC_KEY_BYTES) { "publicKey must be 32 bytes" }
        val fields = ArrayList<Pair<Cbor, Cbor>>()
        fields += Cbor.Uint(1L) to Cbor.Tagged(UUID_TAG, Cbor.Bytes(requestId))
        fields += Cbor.Uint(2L) to Cbor.Bytes(signature)
        if (publicKey != null) fields += Cbor.Uint(3L) to Cbor.Bytes(publicKey)
        return Cbor.encode(Cbor.Dict(fields))
    }
}
