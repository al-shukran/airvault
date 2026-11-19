package app.lensly.core.ur

import com.sparrowwallet.hummingbird.UR

/**
 * A parsed ur:algo-signature, the answer that [AlgoSignatureEncoder] writes: the request it echoes, the 64-byte Ed25519
 * signature, and the public key when the signer sent one. A scanned UR comes from a QR code, so [decode] treats its CBOR as
 * untrusted input.
 */
class AlgoSignature(
    /** The 16-byte request ID that the signature echoes. The caller checks it against the request it sent. */
    val requestId: ByteArray,
    /** The 64-byte Ed25519 signature over the signed bytes. */
    val signature: ByteArray,
    /** The 32-byte Ed25519 public key, when the signer sent one. Null otherwise. */
    val publicKey: ByteArray?,
) {
    /** Outcome of [decode]. Decoding never throws, so every failure comes back as [Rejected]. */
    sealed interface Result {
        class Ok(val signature: AlgoSignature) : Result

        class Rejected(val reason: String) : Result
    }

    companion object {
        /** Decodes a scanned ur:algo-signature. A UR of any other type is rejected. */
        fun decode(ur: UR): Result {
            if (ur.type != AlgoSignatureEncoder.UR_TYPE) {
                return Result.Rejected("UR type ${ur.type} is not ${AlgoSignatureEncoder.UR_TYPE}")
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

        private fun parse(cbor: ByteArray): AlgoSignature {
            val dict = Cbor.decode(cbor) as? Cbor.Dict ?: malformed("payload is not a CBOR map")
            val fields = HashMap<Long, Cbor>()
            for ((key, value) in dict.entries) {
                val number = (key as? Cbor.Uint ?: malformed("map key must be an unsigned integer")).value
                if (number !in 1L..3L) malformed("unknown map key $number")
                if (fields.put(number, value) != null) malformed("duplicate map key $number")
            }

            val requestId = requestIdOf(fields[1L] ?: malformed("requestId is missing"))
            val signature = (fields[2L] ?: malformed("signature is missing")).let {
                (it as? Cbor.Bytes ?: malformed("signature must be a byte string")).value
            }
            if (signature.size != AlgoSignatureEncoder.SIGNATURE_BYTES) malformed("signature must be 64 bytes")
            val publicKey = fields[3L]?.let {
                val bytes = (it as? Cbor.Bytes ?: malformed("publicKey must be a byte string")).value
                if (bytes.size != AlgoSignatureEncoder.PUBLIC_KEY_BYTES) malformed("publicKey must be 32 bytes")
                bytes
            }
            return AlgoSignature(requestId, signature, publicKey)
        }

        /** The request ID, which must carry tag 37 and hold 16 bytes. */
        private fun requestIdOf(item: Cbor): ByteArray {
            val tagged = item as? Cbor.Tagged
            if (tagged == null || tagged.tag != UUID_TAG) malformed("requestId must use tag 37")
            val bytes = (tagged.value as? Cbor.Bytes ?: malformed("requestId must be a byte string")).value
            if (bytes.size != 16) malformed("requestId must be 16 bytes")
            return bytes
        }
    }
}
