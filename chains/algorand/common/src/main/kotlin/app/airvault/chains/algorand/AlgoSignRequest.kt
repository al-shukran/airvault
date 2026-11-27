package app.airvault.chains.algorand

// Registry tags: algo-sign-request 1501, algo-signature 1502. Neither number appears in Keystone's registry lists
// (keystone-sdk-rust registry_types.rs and the keystone-sdk-base ur-registry-* packages, checked 2026-11-17). Like Keystone's
// 1101 and 1102, the number names the type in the registry and is not written into the payload, which is a plain CBOR map.

import app.airvault.core.model.isSingleLinePrintable
import app.airvault.core.signer.UNKNOWN_REQUEST
import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.MalformedPayloadException
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.UUID_TAG
import app.lensly.core.ur.keyPathOf
import app.lensly.core.ur.malformed
import com.sparrowwallet.hummingbird.UR

/** What the wallet wants signed, as key 6 of an algo-sign-request. */
enum class AlgoSignType(val wire: Int) {
    /** A canonical msgpack transaction. Lensly signs "TX" followed by these bytes. */
    TRANSACTION(1),

    /** Message bytes, which the owner reads as text before signing. */
    MESSAGE(2);

    companion object {
        fun fromWire(value: Long): AlgoSignType? = entries.firstOrNull { it.wire.toLong() == value }
    }
}

/**
 * A parsed ur:algo-sign-request. Every key is required. The derivation path must be the Ledger Algorand shape
 * m/44'/283'/N'/0/0 (O3), so the caller only checks that N' is the account it holds.
 */
class AlgoSignRequest(
    /** 16-byte UUID that the response must echo. */
    val requestId: ByteArray,
    /** Bytes to sign, at most [MAX_SIGN_DATA_BYTES]. */
    val signData: ByteArray,
    /** The steps 44', 283', N', 0, 0. */
    val derivationPath: List<PathStep>,
    /** Fingerprint of the root key that [derivationPath] is relative to. Null when the wallet sent none. */
    val sourceFingerprint: Int?,
    /** The Algorand address the wallet asks for, as base32 text. */
    val address: String,
    /** The origin the wallet names, such as "Lumio". */
    val origin: String,
    val signType: AlgoSignType,
) {
    /** Outcome of [decode]. Decoding never throws, so every failure comes back as [Rejected]. */
    sealed interface Result {
        class Ok(val request: AlgoSignRequest) : Result

        class Rejected(val reason: String) : Result
    }

    companion object {
        const val UR_TYPE = "algo-sign-request"

        /** The reason for an origin with a character Lensly cannot show. EVM and Solana refuse such an origin with the same words. */
        const val UNREADABLE_ORIGIN = "The request names an origin with a character Lensly cannot show."

        /** Largest signData accepted: 64 KiB. */
        const val MAX_SIGN_DATA_BYTES = ParserLimits.MAX_SIGN_DATA_BYTES

        // The other fields are small, so the whole payload has the same fixed headroom as a sol-sign-request.
        const val MAX_PAYLOAD_BYTES = MAX_SIGN_DATA_BYTES + 4096
        private const val REQUEST_ID_BYTES = 16
        private const val PURPOSE_HARDENED = 44
        private const val ALGORAND_COIN_TYPE_HARDENED = 283

        /** Decodes the CBOR payload of an algo-sign-request UR. */
        fun decode(urCbor: ByteArray): Result {
            if (urCbor.size > MAX_PAYLOAD_BYTES) {
                return Result.Rejected("payload exceeds $MAX_PAYLOAD_BYTES bytes")
            }
            return try {
                Result.Ok(parse(urCbor))
            } catch (e: MalformedPayloadException) {
                Result.Rejected(e.message ?: "malformed payload")
            } catch (e: RuntimeException) {
                // Last-resort guard so that no parser bug escapes a QR scan.
                Result.Rejected("malformed payload")
            }
        }

        /** Decodes a scanned UR, refusing any type other than algo-sign-request. */
        fun decode(ur: UR): Result =
            if (ur.type == UR_TYPE) {
                decode(ur.cborBytes)
            } else {
                Result.Rejected("UR type ${ur.type} is not $UR_TYPE")
            }

        private fun parse(urCbor: ByteArray): AlgoSignRequest {
            val fields = fieldsOf(Cbor.decode(urCbor))

            val requestId = requestIdOf(fields[1L] ?: malformed("requestId is missing"))

            val signData = bytesOf(fields[2L] ?: malformed("signData is missing"), "signData")
            if (signData.isEmpty()) malformed("signData is empty")
            if (signData.size > MAX_SIGN_DATA_BYTES) malformed("signData exceeds $MAX_SIGN_DATA_BYTES bytes")

            val (path, sourceFingerprint) = keyPathOf(fields[3L] ?: malformed("derivationPath is missing"))
            checkLedgerPath(path)

            val address = textOf(fields[4L] ?: malformed("address is missing"), "address")
            val origin = textOf(fields[5L] ?: malformed("origin is missing"), "origin")
            if (origin.toByteArray(Charsets.UTF_8).size > ParserLimits.MAX_ORIGIN_BYTES) {
                malformed("origin exceeds ${ParserLimits.MAX_ORIGIN_BYTES} bytes")
            }
            // The origin is the wallet's claim, and the review shows it as text. A claim the owner cannot read is refused here.
            if (!isSingleLinePrintable(origin)) malformed(UNREADABLE_ORIGIN)

            val typeValue = uintOf(fields[6L] ?: malformed("signType is missing"), "signType")
            val signType = AlgoSignType.fromWire(typeValue) ?: malformed("unsupported signType $typeValue")

            return AlgoSignRequest(requestId, signData, path, sourceFingerprint, address, origin, signType)
        }

        /** m/44'/283'/N'/0/0, the Ledger Algorand app's scheme. Lensly signs with no other path. */
        private fun checkLedgerPath(path: List<PathStep>) {
            if (path.size != 5) malformed("derivationPath must be 44'/283'/N'/0/0")
            if (path[0] != PathStep(PURPOSE_HARDENED, true) || path[1] != PathStep(ALGORAND_COIN_TYPE_HARDENED, true)) {
                malformed("derivationPath must start with 44'/283'")
            }
            if (!path[2].hardened) malformed("the account step must be hardened")
            if (path[3] != PathStep(0, false) || path[4] != PathStep(0, false)) malformed("derivationPath must end in 0/0")
        }

        private fun fieldsOf(root: Cbor): Map<Long, Cbor> {
            val dict = root as? Cbor.Dict ?: malformed("payload is not a CBOR map")
            val fields = HashMap<Long, Cbor>()
            for ((key, value) in dict.entries) {
                val number = uintOf(key, "map key")
                // Refused, not ignored: the owner must not sign a request with a field the review does not show.
                if (number !in 1L..6L) malformed(UNKNOWN_REQUEST)
                if (fields.put(number, value) != null) malformed("duplicate map key $number")
            }
            return fields
        }

        private fun requestIdOf(item: Cbor): ByteArray {
            val tagged = item as? Cbor.Tagged
            if (tagged == null || tagged.tag != UUID_TAG) malformed("requestId must use tag 37")
            val bytes = bytesOf(tagged.value, "requestId")
            if (bytes.size != REQUEST_ID_BYTES) malformed("requestId must be 16 bytes")
            return bytes
        }

        private fun uintOf(item: Cbor, name: String): Long =
            (item as? Cbor.Uint ?: malformed("$name must be an unsigned integer")).value

        private fun bytesOf(item: Cbor, name: String): ByteArray =
            (item as? Cbor.Bytes ?: malformed("$name must be a byte string")).value

        private fun textOf(item: Cbor, name: String): String =
            (item as? Cbor.Text ?: malformed("$name must be text")).value
    }
}
