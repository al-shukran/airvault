package app.lensly.core.evm

import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.DataType
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

/** An EIP-2930 access list entry: an address and the storage keys it warms, both as 0x hex. */
data class AccessListEntry(val address: String, val storageKeys: List<String>)

/** The transaction types Lensly signs: legacy (EIP-155), EIP-2930 (type 1) and EIP-1559 (type 2). */
enum class TxType { LEGACY_EIP155, EIP1559, EIP2930 }

/**
 * An unsigned transaction decoded from the bytes the wallet sent, for the confirmation screen.
 * Values are exactly what gets signed.
 */
data class UnsignedTx(
    val type: TxType,
    val chainId: BigInteger,
    val nonce: BigInteger,
    /** EIP-55 checksummed address, or null for contract creation. */
    val to: String?,
    /** Value in wei. */
    val value: BigInteger,
    /** Calldata as 0x-prefixed lowercase hex, "0x" when empty. */
    val data: String,
    val gasLimit: BigInteger,
    /** Legacy gas price in wei. Null for EIP-1559. */
    val gasPrice: BigInteger?,
    /** EIP-1559 priority fee per gas in wei. Null for legacy. */
    val maxPriorityFeePerGas: BigInteger?,
    /** EIP-1559 max fee per gas in wei. Null for legacy. */
    val maxFeePerGas: BigInteger?,
    /** The access list of an EIP-2930 or EIP-1559 transaction. Empty when there is none. */
    val accessList: List<AccessListEntry> = emptyList(),
) {
    /** Outcome of [decode]. Decoding never throws. */
    sealed interface Result {
        class Ok(val tx: UnsignedTx) : Result

        class Rejected(val reason: String) : Result
    }

    /**
     * The unsigned payload of this transaction, rebuilt from its fields in the form [decode] reads. The review shows these
     * fields, so the payload is what gets signed when the review shows what is signed. Null when a field is not in that form:
     * a to that is not its checksum address, calldata that is not lowercase hex, or fees that do not fit the type.
     */
    fun encode(): ByteArray? {
        if (chainId.signum() <= 0 || nonce.signum() < 0 || value.signum() < 0 || gasLimit.signum() < 0) return null
        val address = to?.let { canonicalAddress(it) ?: return null } ?: ByteArray(0)
        val calldata = canonicalCalldata(data) ?: return null
        return when (type) {
            TxType.LEGACY_EIP155 -> {
                if (gasPrice == null || maxPriorityFeePerGas != null || maxFeePerGas != null || accessList.isNotEmpty()) return null
                Rlp.encodeList(
                    listOf(
                        rlpInteger(nonce), rlpInteger(gasPrice), rlpInteger(gasLimit), Rlp.encodeString(address),
                        rlpInteger(value), Rlp.encodeString(calldata), rlpInteger(chainId), Rlp.encodeString(ByteArray(0)),
                        Rlp.encodeString(ByteArray(0)),
                    ),
                )
            }
            TxType.EIP2930 -> {
                if (gasPrice == null || maxPriorityFeePerGas != null || maxFeePerGas != null) return null
                byteArrayOf(TYPED_EIP2930.toByte()) + Rlp.encodeList(
                    listOf(
                        rlpInteger(chainId), rlpInteger(nonce), rlpInteger(gasPrice), rlpInteger(gasLimit),
                        Rlp.encodeString(address), rlpInteger(value), Rlp.encodeString(calldata),
                        accessListRlp(accessList) ?: return null,
                    ),
                )
            }
            TxType.EIP1559 -> {
                if (gasPrice != null || maxPriorityFeePerGas == null || maxFeePerGas == null) return null
                byteArrayOf(TYPED_EIP1559.toByte()) + Rlp.encodeList(
                    listOf(
                        rlpInteger(chainId), rlpInteger(nonce), rlpInteger(maxPriorityFeePerGas), rlpInteger(maxFeePerGas),
                        rlpInteger(gasLimit), Rlp.encodeString(address), rlpInteger(value), Rlp.encodeString(calldata),
                        accessListRlp(accessList) ?: return null,
                    ),
                )
            }
        }
    }

    companion object {
        private const val TYPED_EIP2930 = 0x01
        private const val TYPED_EIP1559 = 0x02
        private const val MAX_INTEGER_BYTES = 32
        private const val ADDRESS_BYTES = 20
        private const val STORAGE_KEY_BYTES = 32

        /**
         * Decodes [signData] by [dataType]. Legacy (1) is the unsigned EIP-155 list
         * [nonce, gasPrice, gas, to, value, data, chainId, 0, 0]. EIP-1559 (4) is 0x02 followed by
         * [chainId, nonce, maxPriorityFee, maxFee, gas, to, value, data, accessList]. Anything else is rejected.
         */
        fun decode(signData: ByteArray, dataType: DataType): Result = try {
            when (dataType) {
                DataType.TRANSACTION -> Result.Ok(legacy(signData))
                DataType.TYPED_TRANSACTION -> Result.Ok(typed(signData))
                else -> Result.Rejected("transactions use dataType 1 or 4, not dataType ${dataType.wire}")
            }
        } catch (e: MalformedTxException) {
            Result.Rejected(e.message ?: "malformed transaction")
        } catch (e: RuntimeException) {
            // Last-resort guard so that no parser bug escapes a QR scan.
            Result.Rejected("malformed transaction")
        }

        private fun legacy(signData: ByteArray): UnsignedTx {
            val fields = (Rlp.decode(signData) as? Rlp.Lst ?: malformedTx("legacy payload is not an RLP list")).items
            if (fields.size != 9) malformedTx("EIP-155 payload must have 9 fields, found ${fields.size}")
            if (!isZero(fields[7]) || !isZero(fields[8])) malformedTx("EIP-155 r and s placeholders must be zero")
            return UnsignedTx(
                type = TxType.LEGACY_EIP155,
                chainId = chainIdOf(fields[6]),
                nonce = unsignedOf(fields[0]),
                to = addressOf(fields[3]),
                value = unsignedOf(fields[4]),
                data = calldataOf(fields[5]),
                gasLimit = unsignedOf(fields[2]),
                gasPrice = unsignedOf(fields[1]),
                maxPriorityFeePerGas = null,
                maxFeePerGas = null,
            )
        }

        private fun typed(signData: ByteArray): UnsignedTx = when (signData.firstOrNull()?.toInt()?.and(0xFF)) {
            TYPED_EIP2930 -> eip2930(signData)
            TYPED_EIP1559 -> eip1559(signData)
            else -> malformedTx("only EIP-2930 (type 0x01) and EIP-1559 (type 0x02) typed transactions are supported")
        }

        private fun eip2930(signData: ByteArray): UnsignedTx {
            val fields = typedFields(signData, 8, "EIP-2930")
            return UnsignedTx(
                type = TxType.EIP2930,
                chainId = chainIdOf(fields[0]),
                nonce = unsignedOf(fields[1]),
                to = addressOf(fields[4]),
                value = unsignedOf(fields[5]),
                data = calldataOf(fields[6]),
                gasLimit = unsignedOf(fields[3]),
                gasPrice = unsignedOf(fields[2]),
                maxPriorityFeePerGas = null,
                maxFeePerGas = null,
                accessList = accessListOf(fields[7]),
            )
        }

        private fun eip1559(signData: ByteArray): UnsignedTx {
            val fields = typedFields(signData, 9, "EIP-1559")
            return UnsignedTx(
                type = TxType.EIP1559,
                chainId = chainIdOf(fields[0]),
                nonce = unsignedOf(fields[1]),
                to = addressOf(fields[5]),
                value = unsignedOf(fields[6]),
                data = calldataOf(fields[7]),
                gasLimit = unsignedOf(fields[4]),
                gasPrice = null,
                maxPriorityFeePerGas = unsignedOf(fields[2]),
                maxFeePerGas = unsignedOf(fields[3]),
                accessList = accessListOf(fields[8]),
            )
        }

        /** The fields of a typed transaction: the RLP list after its type byte, which must hold [count] fields. */
        private fun typedFields(signData: ByteArray, count: Int, name: String): List<Rlp> {
            val payload = signData.copyOfRange(1, signData.size)
            val fields = (Rlp.decode(payload) as? Rlp.Lst ?: malformedTx("$name payload is not an RLP list")).items
            if (fields.size != count) malformedTx("$name payload must have $count fields, found ${fields.size}")
            return fields
        }

        /** An access list: a list of [address, [storage keys]] entries, each address 20 bytes and each storage key 32 bytes. */
        private fun accessListOf(item: Rlp): List<AccessListEntry> {
            val entries = (item as? Rlp.Lst ?: malformedTx("access list must be an RLP list")).items
            return entries.map { entry ->
                val pair = (entry as? Rlp.Lst ?: malformedTx("access list entry must be an RLP list")).items
                if (pair.size != 2) malformedTx("access list entry must hold an address and its storage keys")
                val keys = (pair[1] as? Rlp.Lst ?: malformedTx("storage keys must be an RLP list")).items
                AccessListEntry(
                    address = fixedHexOf(pair[0], ADDRESS_BYTES, "access list address"),
                    storageKeys = keys.map { fixedHexOf(it, STORAGE_KEY_BYTES, "storage key") },
                )
            }
        }

        private fun fixedHexOf(item: Rlp, size: Int, name: String): String {
            val bytes = bytesOf(item)
            if (bytes.size != size) malformedTx("$name must be $size bytes")
            return hexOf(bytes)
        }

        private fun bytesOf(item: Rlp): ByteArray =
            (item as? Rlp.Str ?: malformedTx("expected a string field")).bytes

        /** Canonical unsigned integer: at most 32 bytes, no leading zero, and zero is the empty string. */
        private fun unsignedOf(item: Rlp): BigInteger {
            val bytes = bytesOf(item)
            if (bytes.size > MAX_INTEGER_BYTES) malformedTx("integer field exceeds 32 bytes")
            if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) malformedTx("integer field has a leading zero")
            return BigInteger(1, bytes)
        }

        private fun chainIdOf(item: Rlp): BigInteger {
            val chainId = unsignedOf(item)
            if (chainId.signum() <= 0) malformedTx("chainId must be positive")
            return chainId
        }

        private fun addressOf(item: Rlp): String? {
            val bytes = bytesOf(item)
            return when (bytes.size) {
                0 -> null
                20 -> Keys.toChecksumAddress(bytes.toHex())
                else -> malformedTx("to must be 20 bytes, or empty for contract creation")
            }
        }

        /** The calldata as 0x-prefixed hex. Calldata longer than [ParserLimits.MAX_CALLDATA_BYTES] is refused. */
        private fun calldataOf(item: Rlp): String {
            val bytes = bytesOf(item)
            if (bytes.size > ParserLimits.MAX_CALLDATA_BYTES) {
                malformedTx("calldata is larger than ${ParserLimits.MAX_CALLDATA_BYTES} bytes")
            }
            return hexOf(bytes)
        }

        private fun isZero(item: Rlp): Boolean = item is Rlp.Str && item.bytes.isEmpty()

        private fun hexOf(bytes: ByteArray): String = "0x" + bytes.toHex()
    }
}

private val CANONICAL_ADDRESS = Regex("0x[0-9a-fA-F]{40}")
private val CANONICAL_CALLDATA = Regex("0x(?:[0-9a-f]{2})*")

/** The bytes of [address] when it is written as its EIP-55 checksum, or null when it is not. */
private fun canonicalAddress(address: String): ByteArray? =
    if (CANONICAL_ADDRESS.matches(address) && Keys.toChecksumAddress(address) == address) {
        Numeric.hexStringToByteArray(address)
    } else {
        null
    }

/** The bytes of [data] when it is lowercase 0x hex, or null when it is not. */
private fun canonicalCalldata(data: String): ByteArray? =
    if (CANONICAL_CALLDATA.matches(data)) Numeric.hexStringToByteArray(data) else null

/**
 * The RLP of an access list, or null when an address or storage key is not 20 or 32 bytes of 0x hex. An empty list is the
 * empty RLP list, which is what a transaction without an access list carries.
 */
private fun accessListRlp(accessList: List<AccessListEntry>): ByteArray? {
    val entries = accessList.map { entry ->
        val address = hexBytes(entry.address, 20) ?: return null
        val keys = entry.storageKeys.map { key -> Rlp.encodeString(hexBytes(key, 32) ?: return null) }
        Rlp.encodeList(listOf(Rlp.encodeString(address), Rlp.encodeList(keys)))
    }
    return Rlp.encodeList(entries)
}

/** The bytes of [text] when it is 0x hex of exactly [size] bytes, or null when it is not. */
private fun hexBytes(text: String, size: Int): ByteArray? =
    if (Regex("0x[0-9a-fA-F]{${2 * size}}").matches(text)) Numeric.hexStringToByteArray(text) else null

/** An unsigned integer as RLP: its minimal big-endian bytes, where zero is the empty string. */
private fun rlpInteger(value: BigInteger): ByteArray =
    Rlp.encodeString(value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
