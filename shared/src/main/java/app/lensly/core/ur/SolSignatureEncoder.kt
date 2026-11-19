package app.lensly.core.ur

import app.airvault.chains.solana.signer.SolSignResponse
import com.sparrowwallet.hummingbird.UR

/**
 * ur:sol-signature, the writing side of the Keystone sol-signature type. The payload is built by [SolSignResponse] in the Solana chain
 * module; this object only frames it. Like Keystone's, it carries no origin.
 */
object SolSignatureEncoder {
    internal const val UR_TYPE = SolSignResponse.UR_TYPE
    internal const val SIGNATURE_BYTES = SolSignResponse.SIGNATURE_BYTES

    /**
     * Uppercase frames for the response. [signature] is the 64-byte Ed25519 signature over the message bytes that the
     * request carried. [requestId] must be the 16-byte UUID from the request. Use the first frame for a single QR code.
     */
    fun encode(
        requestId: ByteArray,
        signature: ByteArray,
        maxFragment: Int = DEFAULT_MAX_FRAGMENT,
    ): Sequence<String> {
        return framesOf(UR(UR_TYPE, SolSignResponse.cbor(requestId, signature)), maxFragment)
    }
}
