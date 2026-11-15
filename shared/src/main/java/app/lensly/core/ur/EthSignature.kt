package app.lensly.core.ur

import com.sparrowwallet.hummingbird.UR

/**
 * A parsed ur:eth-signature, the answer that [EthSignatureEncoder] writes: the request it echoes, and r || s || v. A scanned UR
 * comes from a QR code, so [decode] treats its CBOR as untrusted input.
 */
class EthSignature(
    /** The 16-byte request ID that the signature echoes. The caller checks it against the request it sent. */
    val requestId: ByteArray,
    /** r (32 bytes) || s (32 bytes) || v. For a typed transaction v is 0 or 1. */
    val signature: ByteArray,
    /** The origin the signer named, such as "Lensly". Null when it named none. */
    val origin: String?,
) {
    /** Outcome of [decode]. Decoding never throws, so every failure comes back as [Rejected]. */
    sealed interface Result {
        class Ok(val signature: EthSignature) : Result

        class Rejected(val reason: String) : Result
    }

    companion object {
        /** Decodes a scanned ur:eth-signature. A UR of any other type is rejected. */
        fun decode(ur: UR): Result {
            if (ur.type != EthSignatureEncoder.UR_TYPE) {
                return Result.Rejected("UR type ${ur.type} is not ${EthSignatureEncoder.UR_TYPE}")
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

        private fun parse(cbor: ByteArray): EthSignature {
            val dict = Cbor.decode(cbor) as? Cbor.Dict ?: malformed("payload is not a CBOR map")
            val fields = HashMap<Long, Cbor>()
            for ((key, value) in dict.entries) {
                val number = (key as? Cbor.Uint ?: malformed("map key must be an unsigned integer")).value
                if (fields.put(number, value) != null) malformed("duplicate map key $number")
            }

            val requestId = requestIdOf(fields[1L] ?: malformed("requestId is missing"))
            val signature = (fields[2L] as? Cbor.Bytes ?: malformed("signature must be a byte string")).value
            if (signature.size !in EthSignatureEncoder.MIN_SIGNATURE_BYTES..EthSignatureEncoder.MAX_SIGNATURE_BYTES) {
                malformed("signature must be r || s || v")
            }
            val origin = fields[3L]?.let { (it as? Cbor.Text ?: malformed("origin must be text")).value }
            return EthSignature(requestId, signature, origin)
        }

        /** The request ID, with or without its tag 37, which must hold 16 bytes. */
        private fun requestIdOf(item: Cbor): ByteArray {
            val inner = if (item is Cbor.Tagged) {
                if (item.tag != EthSignRequest.UUID_TAG) malformed("requestId must use tag 37")
                item.value
            } else {
                item
            }
            val bytes = (inner as? Cbor.Bytes ?: malformed("requestId must be a byte string")).value
            if (bytes.size != 16) malformed("requestId must be 16 bytes")
            return bytes
        }
    }
}
