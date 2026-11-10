package app.lensly.core.ur

import com.sparrowwallet.hummingbird.UR

/** What the wallet wants signed, as key 3 of an eth-sign-request. */
enum class DataType(val wire: Int) {
    /** Unsigned legacy or EIP-155 transaction RLP. */
    TRANSACTION(1),

    /** EIP-712 typed data as JSON. Decoded, but the signer refuses it. */
    TYPED_DATA(2),

    /** personal_sign message bytes. */
    PERSONAL_MESSAGE(3),

    /** EIP-2718 typed transaction: 0x02 followed by RLP. */
    TYPED_TRANSACTION(4);

    companion object {
        fun fromWire(value: Long): DataType? = entries.firstOrNull { it.wire.toLong() == value }
    }
}

/** One derivation step. For example, 44' is PathStep(index = 44, hardened = true). */
data class PathStep(val index: Int, val hardened: Boolean)

/** A parsed ur:eth-sign-request. Optional fields are null when the wallet left them out. */
class EthSignRequest(
    /** 16-byte UUID that the response must echo. Null when the wallet omitted it. */
    val requestId: ByteArray?,
    /** Bytes to sign, at most [MAX_SIGN_DATA_BYTES]. */
    val signData: ByteArray,
    val dataType: DataType,
    val chainId: Long?,
    val derivationPath: List<PathStep>,
    /** Fingerprint of the root key that [derivationPath] is relative to. */
    val sourceFingerprint: Int?,
    /** 20-byte address the wallet asks for. Rabby sends it and MetaMask does not. */
    val address: ByteArray?,
    val origin: String?,
) {
    /** Outcome of [decode]. Decoding never throws, so every failure comes back as [Rejected]. */
    sealed interface Result {
        class Ok(val request: EthSignRequest) : Result

        class Rejected(val reason: String) : Result
    }

    companion object {
        const val UR_TYPE = "eth-sign-request"

        /** Largest signData accepted: 64 KiB. */
        const val MAX_SIGN_DATA_BYTES = 64 * 1024

        // The other fields are small, so the whole payload has a fixed headroom above the sign data.
        private const val MAX_PAYLOAD_BYTES = MAX_SIGN_DATA_BYTES + 4096
        private const val UUID_TAG = 37L
        private const val KEYPATH_TAG = 304L
        private const val MAX_PATH_STEPS = 16
        private const val MAX_INDEX = Int.MAX_VALUE.toLong()
        private const val MAX_FINGERPRINT = 0xFFFFFFFFL

        /** Decodes the CBOR payload of an eth-sign-request UR. */
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

        /** Decodes a scanned UR, refusing any type other than eth-sign-request. */
        fun decode(ur: UR): Result =
            if (ur.type == UR_TYPE) {
                decode(ur.cborBytes)
            } else {
                Result.Rejected("UR type ${ur.type} is not $UR_TYPE")
            }

        private fun parse(urCbor: ByteArray): EthSignRequest {
            val fields = fieldsOf(Cbor.decode(urCbor))

            val requestId = fields[1L]?.let { requestIdOf(it) }

            val signData = bytesOf(fields[2L] ?: malformed("signData is missing"), "signData")
            if (signData.isEmpty()) malformed("signData is empty")
            if (signData.size > MAX_SIGN_DATA_BYTES) malformed("signData exceeds $MAX_SIGN_DATA_BYTES bytes")

            val typeValue = uintOf(fields[3L] ?: malformed("dataType is missing"), "dataType")
            val dataType = DataType.fromWire(typeValue) ?: malformed("unsupported dataType $typeValue")

            val chainId = fields[4L]?.let { uintOf(it, "chainId") }

            val (path, sourceFingerprint) = keyPathOf(fields[5L] ?: malformed("derivationPath is missing"))

            val address = fields[6L]?.let {
                bytesOf(it, "address").also { bytes -> if (bytes.size != 20) malformed("address must be 20 bytes") }
            }

            val origin = fields[7L]?.let { textOf(it, "origin") }

            return EthSignRequest(requestId, signData, dataType, chainId, path, sourceFingerprint, address, origin)
        }

        private fun fieldsOf(root: Cbor): Map<Long, Cbor> {
            val dict = root as? Cbor.Dict ?: malformed("payload is not a CBOR map")
            val fields = HashMap<Long, Cbor>()
            for ((key, value) in dict.entries) {
                val number = uintOf(key, "map key")
                if (fields.put(number, value) != null) malformed("duplicate map key $number")
            }
            return fields
        }

        private fun requestIdOf(item: Cbor): ByteArray {
            val inner = if (item is Cbor.Tagged) {
                if (item.tag != UUID_TAG) malformed("requestId must use tag 37")
                item.value
            } else {
                item
            }
            val bytes = bytesOf(inner, "requestId")
            if (bytes.size != 16) malformed("requestId must be 16 bytes")
            return bytes
        }

        private fun keyPathOf(item: Cbor): Pair<List<PathStep>, Int?> {
            val tagged = item as? Cbor.Tagged
            if (tagged == null || tagged.tag != KEYPATH_TAG) malformed("derivationPath must be a tag 304 keypath")
            val fields = fieldsOf(tagged.value)

            val components = (fields[1L] as? Cbor.Arr ?: malformed("keypath components are missing")).items
            if (components.isEmpty() || components.size % 2 != 0 || components.size > 2 * MAX_PATH_STEPS) {
                malformed("keypath has an invalid component count")
            }
            // Wildcard, range and pair components are refused. Only plain index steps can be signed.
            val steps = components.chunked(2).map { (index, hardened) ->
                val number = uintOf(index, "path index")
                if (number > MAX_INDEX) malformed("path index out of range")
                val isHardened = (hardened as? Cbor.Bool ?: malformed("path step is missing its hardened flag")).value
                PathStep(number.toInt(), isHardened)
            }

            val sourceFingerprint = fields[2L]?.let {
                val number = uintOf(it, "source fingerprint")
                if (number > MAX_FINGERPRINT) malformed("source fingerprint exceeds 32 bits")
                number.toInt()
            }
            return steps to sourceFingerprint
        }

        private fun uintOf(item: Cbor, name: String): Long =
            (item as? Cbor.Uint ?: malformed("$name must be an unsigned integer")).value

        private fun bytesOf(item: Cbor, name: String): ByteArray =
            (item as? Cbor.Bytes ?: malformed("$name must be a byte string")).value

        private fun textOf(item: Cbor, name: String): String =
            (item as? Cbor.Text ?: malformed("$name must be text")).value
    }
}
