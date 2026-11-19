package app.airvault.chains.solana.signer

import app.lensly.core.ur.Cbor

/**
 * The payload of ur:sol-signature, the answer to a sol-sign-request. Key 1 is the request UUID (tag 37) and key 2 is the 64-byte Ed25519
 * signature. Like Keystone's, it carries no origin. The core frames the payload as a UR.
 */
object SolSignResponse {
    const val UR_TYPE = "sol-signature"
    const val SIGNATURE_BYTES = 64
    private const val UUID_TAG = 37L
    private const val REQUEST_ID_BYTES = 16

    /** The CBOR payload that answers request [requestId] with [signature], the 64-byte Ed25519 signature over the sign data. */
    fun cbor(requestId: ByteArray, signature: ByteArray): ByteArray {
        require(requestId.size == REQUEST_ID_BYTES) { "requestId must be 16 bytes" }
        require(signature.size == SIGNATURE_BYTES) { "signature must be 64 bytes" }
        return Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(1L) to Cbor.Tagged(UUID_TAG, Cbor.Bytes(requestId)),
                    Cbor.Uint(2L) to Cbor.Bytes(signature),
                ),
            ),
        )
    }
}
