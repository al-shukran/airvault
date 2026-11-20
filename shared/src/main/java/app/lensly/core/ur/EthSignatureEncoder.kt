package app.lensly.core.ur

import app.airvault.chains.evm.signer.EvmSignResponse
import com.sparrowwallet.hummingbird.UR

/**
 * ur:eth-signature, the writing side of the eth-signature type. The payload is built by [EvmSignResponse] in the EVM chain module; this object
 * only frames it.
 */
object EthSignatureEncoder {
    const val DEFAULT_ORIGIN = EvmSignResponse.DEFAULT_ORIGIN

    internal const val UR_TYPE = EvmSignResponse.UR_TYPE
    internal const val MIN_SIGNATURE_BYTES = 65
    // Legacy v (chainId * 2 + 35 + recId) is at most 8 bytes for any realistic chain ID.
    internal const val MAX_SIGNATURE_BYTES = 80

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
        return framesOf(UR(UR_TYPE, EvmSignResponse.cbor(requestId, signature, origin)), maxFragment)
    }
}
