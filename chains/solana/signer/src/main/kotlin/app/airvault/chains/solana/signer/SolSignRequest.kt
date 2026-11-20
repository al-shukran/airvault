package app.airvault.chains.solana.signer

import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.MalformedPayloadException
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.keyPathOf
import app.lensly.core.ur.malformed
import com.sparrowwallet.hummingbird.UR

/** What the wallet wants signed, as key 6 of a sol-sign-request. */
enum class SolSignType(val wire: Int) {
    /** A serialized Solana transaction message. */
    TRANSACTION(1),

    /** An off-chain message, which the owner reads as text before signing. */
    MESSAGE(2);

    companion object {
        fun fromWire(value: Long): SolSignType? = entries.firstOrNull { it.wire.toLong() == value }
    }
}

/** The Solana cluster a request is for, as key 7 of a sol-sign-request. Lumio states it. A request that names none has no cluster. */
enum class SolCluster(val wire: String) {
    MAINNET_BETA("mainnet-beta"),
    DEVNET("devnet"),
    TESTNET("testnet");

    companion object {
        fun fromWire(value: String): SolCluster? = entries.firstOrNull { it.wire == value }
    }
}

/** A parsed ur:sol-sign-request. Optional fields are null when the wallet left them out. */
class SolSignRequest(
    /** 16-byte UUID that the response must echo. Null when the wallet omitted it. */
    val requestId: ByteArray?,
    /** Bytes to sign, at most [MAX_SIGN_DATA_BYTES]. */
    val signData: ByteArray,
    /** Every step is hardened and the path starts at 44'/501'. The caller decides which account index it matches. */
    val derivationPath: List<PathStep>,
    /** Fingerprint of the root key that [derivationPath] is relative to. */
    val sourceFingerprint: Int?,
    /** 32-byte Ed25519 public key the wallet asks for. Null when the wallet sent none. */
    val address: ByteArray?,
    /** The origin the wallet names, such as "solflare". Null when it names none. */
    val origin: String?,
    val signType: SolSignType,
    /** The cluster the wallet states, or null when it names none. Keystone's requests name none. */
    val cluster: SolCluster? = null,
) {
    /** Outcome of [decode]. Decoding never throws, so every failure comes back as [Rejected]. */
    sealed interface Result {
        class Ok(val request: SolSignRequest) : Result

        class Rejected(val reason: String) : Result
    }

    companion object {
        const val UR_TYPE = "sol-sign-request"

        /** Largest signData accepted: 64 KiB. */
        const val MAX_SIGN_DATA_BYTES = ParserLimits.MAX_SIGN_DATA_BYTES

        // The other fields are small, so the whole payload has the same fixed headroom as an eth-sign-request.
        const val MAX_PAYLOAD_BYTES = MAX_SIGN_DATA_BYTES + 4096
        private const val UUID_TAG = 37L
        private const val REQUEST_ID_BYTES = 16
        private const val ADDRESS_BYTES = 32
        private const val PURPOSE_HARDENED = 44
        private const val SOLANA_COIN_TYPE_HARDENED = 501

        /** Decodes the CBOR payload of a sol-sign-request UR. */
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

        /** Decodes a scanned UR, refusing any type other than sol-sign-request. */
        fun decode(ur: UR): Result =
            if (ur.type == UR_TYPE) {
                decode(ur.cborBytes)
            } else {
                Result.Rejected("UR type ${ur.type} is not $UR_TYPE")
            }

        private fun parse(urCbor: ByteArray): SolSignRequest {
            val fields = fieldsOf(Cbor.decode(urCbor))

            val requestId = fields[1L]?.let { requestIdOf(it) }

            val signData = bytesOf(fields[2L] ?: malformed("signData is missing"), "signData")
            if (signData.isEmpty()) malformed("signData is empty")
            if (signData.size > MAX_SIGN_DATA_BYTES) malformed("signData exceeds $MAX_SIGN_DATA_BYTES bytes")

            val (path, sourceFingerprint) = keyPathOf(fields[3L] ?: malformed("derivationPath is missing"))
            if (path.any { !it.hardened }) malformed("derivationPath must be hardened at every step")
            if (path.size < 2 || path[0] != PathStep(PURPOSE_HARDENED, true) || path[1] != PathStep(SOLANA_COIN_TYPE_HARDENED, true)) {
                malformed("derivationPath must start with 44'/501'")
            }

            // Keystone sends an empty byte string when it has no address. An empty value means the wallet left it out.
            val address = fields[4L]?.let {
                bytesOf(it, "address").takeIf { bytes -> bytes.isNotEmpty() }?.also { bytes ->
                    if (bytes.size != ADDRESS_BYTES) malformed("address must be 32 bytes")
                }
            }

            val origin = fields[5L]?.let { textOf(it, "origin") }
            if (origin != null && origin.toByteArray(Charsets.UTF_8).size > ParserLimits.MAX_ORIGIN_BYTES) {
                malformed("origin exceeds ${ParserLimits.MAX_ORIGIN_BYTES} bytes")
            }

            val typeValue = uintOf(fields[6L] ?: malformed("signType is missing"), "signType")
            val signType = SolSignType.fromWire(typeValue) ?: malformed("unsupported signType $typeValue")

            // The cluster is optional. A name Lensly does not hold is refused, so the review never shows a network it cannot name.
            val cluster = fields[7L]?.let { SolCluster.fromWire(textOf(it, "cluster")) ?: malformed("unsupported cluster") }

            return SolSignRequest(requestId, signData, path, sourceFingerprint, address, origin, signType, cluster)
        }

        private fun fieldsOf(root: Cbor): Map<Long, Cbor> {
            val dict = root as? Cbor.Dict ?: malformed("payload is not a CBOR map")
            val fields = HashMap<Long, Cbor>()
            for ((key, value) in dict.entries) {
                val number = uintOf(key, "map key")
                if (number !in 1L..7L) malformed("unknown map key $number")
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
