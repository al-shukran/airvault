package app.lensly.core.ur

import com.sparrowwallet.hummingbird.UR

/**
 * A parsed ur:sol-signature, the answer that [SolSignatureEncoder] writes: the request it echoes, and the 64-byte Ed25519 signature. A scanned UR
 * comes from a QR code, so [decode] treats its CBOR as untrusted input.
 */
class SolSignature(
    /** The 16-byte request ID that the signature echoes. The caller checks it against the request it sent. */
    val requestId: ByteArray,
    /** The 64-byte Ed25519 signature of the message that the request carried. The caller checks it against the account's key. */
    val signature: ByteArray,
) {
    /** Outcome of [decode]. Decoding never throws, so every failure comes back as [Rejected]. */
    sealed interface Result {
        class Ok(val signature: SolSignature) : Result

        class Rejected(val reason: String) : Result
    }

    companion object {
        private const val UUID_TAG = 37L
        private const val REQUEST_ID_BYTES = 16

        /** Decodes a scanned ur:sol-signature. A UR of any other type is rejected. */
        fun decode(ur: UR): Result {
            if (ur.type != SolSignatureEncoder.UR_TYPE) {
                return Result.Rejected("UR type ${ur.type} is not ${SolSignatureEncoder.UR_TYPE}")
            }
            return try {
                Result.Ok(parse(ur.cborBytes))
            } catch (e: MalformedPayloadException) {
                Result.Rejected(e.message ?: "malformed payload")
            } catch (e: RuntimeException) {
                // Last-resort guard so that no parser bug escapes a QR scan.
                Result.Rejected("malformed payload")
            }
        }

        private fun parse(cbor: ByteArray): SolSignature {
            val dict = Cbor.decode(cbor) as? Cbor.Dict ?: malformed("payload is not a CBOR map")
            val fields = HashMap<Long, Cbor>()
            for ((key, value) in dict.entries) {
                val number = (key as? Cbor.Uint ?: malformed("map key must be an unsigned integer")).value
                if (fields.put(number, value) != null) malformed("duplicate map key $number")
            }
            val requestId = requestIdOf(fields[1L] ?: malformed("requestId is missing"))
            val signature = (fields[2L] as? Cbor.Bytes ?: malformed("signature must be a byte string")).value
            if (signature.size != SolSignatureEncoder.SIGNATURE_BYTES) malformed("signature must be 64 bytes")
            return SolSignature(requestId, signature)
        }

        /** The request ID, with or without its tag 37, which must hold 16 bytes. */
        private fun requestIdOf(item: Cbor): ByteArray {
            val inner = if (item is Cbor.Tagged) {
                if (item.tag != UUID_TAG) malformed("requestId must use tag 37")
                item.value
            } else {
                item
            }
            val bytes = (inner as? Cbor.Bytes ?: malformed("requestId must be a byte string")).value
            if (bytes.size != REQUEST_ID_BYTES) malformed("requestId must be 16 bytes")
            return bytes
        }
    }
}
