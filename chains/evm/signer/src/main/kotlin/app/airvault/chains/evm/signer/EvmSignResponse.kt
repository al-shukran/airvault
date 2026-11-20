package app.airvault.chains.evm.signer

import app.lensly.core.ur.Cbor

/**
 * The payload of ur:eth-signature, the answer to an eth-sign-request. Key 1 is the request UUID (tag 37), key 2 is r || s || v, and key 3 is the
 * origin the signer names itself by. The core frames the payload as a UR.
 */
object EvmSignResponse {
    const val UR_TYPE = "eth-signature"

    /** The origin Lensly names itself by in its responses. */
    const val DEFAULT_ORIGIN = "Lensly"

    private const val UUID_TAG = 37L
    private const val REQUEST_ID_BYTES = 16

    /**
     * The CBOR payload that answers request [requestId] with [signature], r (32) || s (32) || v. For typed transactions v is 0 or 1, for personal
     * messages and typed data it is 27 or 28, and for legacy transactions it is the minimal big-endian EIP-155 value.
     */
    fun cbor(requestId: ByteArray, signature: ByteArray, origin: String = DEFAULT_ORIGIN): ByteArray {
        require(requestId.size == REQUEST_ID_BYTES) { "requestId must be 16 bytes" }
        return Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(1L) to Cbor.Tagged(UUID_TAG, Cbor.Bytes(requestId)),
                    Cbor.Uint(2L) to Cbor.Bytes(signature),
                    Cbor.Uint(3L) to Cbor.Text(origin),
                ),
            ),
        )
    }
}
