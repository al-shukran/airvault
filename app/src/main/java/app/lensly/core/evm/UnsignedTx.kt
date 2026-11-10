package app.lensly.core.evm

import app.lensly.core.ur.DataType
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import org.web3j.crypto.Keys

enum class TxType { LEGACY_EIP155, EIP1559 }

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
) {
    /** Outcome of [decode]. Decoding never throws. */
    sealed interface Result {
        class Ok(val tx: UnsignedTx) : Result

        class Rejected(val reason: String) : Result
    }

    companion object {
        private const val TYPED_EIP1559 = 0x02
        private const val MAX_INTEGER_BYTES = 32

        /**
         * Decodes [signData] by [dataType]. Legacy (1) is the unsigned EIP-155 list
         * [nonce, gasPrice, gas, to, value, data, chainId, 0, 0]. EIP-1559 (4) is 0x02 followed by
         * [chainId, nonce, maxPriorityFee, maxFee, gas, to, value, data, accessList]. Anything else is rejected.
         */
        fun decode(signData: ByteArray, dataType: DataType): Result = try {
            when (dataType) {
                DataType.TRANSACTION -> Result.Ok(legacy(signData))
                DataType.TYPED_TRANSACTION -> Result.Ok(eip1559(signData))
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
                data = hexOf(bytesOf(fields[5])),
                gasLimit = unsignedOf(fields[2]),
                gasPrice = unsignedOf(fields[1]),
                maxPriorityFeePerGas = null,
                maxFeePerGas = null,
            )
        }

        private fun eip1559(signData: ByteArray): UnsignedTx {
            if (signData.isEmpty() || (signData[0].toInt() and 0xFF) != TYPED_EIP1559) {
                malformedTx("only EIP-1559 typed transactions (type 0x02) are supported")
            }
            val payload = signData.copyOfRange(1, signData.size)
            val fields = (Rlp.decode(payload) as? Rlp.Lst ?: malformedTx("EIP-1559 payload is not an RLP list")).items
            if (fields.size != 9) malformedTx("EIP-1559 payload must have 9 fields, found ${fields.size}")
            if (fields[8] !is Rlp.Lst) malformedTx("access list must be an RLP list")
            return UnsignedTx(
                type = TxType.EIP1559,
                chainId = chainIdOf(fields[0]),
                nonce = unsignedOf(fields[1]),
                to = addressOf(fields[5]),
                value = unsignedOf(fields[6]),
                data = hexOf(bytesOf(fields[7])),
                gasLimit = unsignedOf(fields[4]),
                gasPrice = null,
                maxPriorityFeePerGas = unsignedOf(fields[2]),
                maxFeePerGas = unsignedOf(fields[3]),
            )
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

        private fun isZero(item: Rlp): Boolean = item is Rlp.Str && item.bytes.isEmpty()

        private fun hexOf(bytes: ByteArray): String = "0x" + bytes.toHex()
    }
}
