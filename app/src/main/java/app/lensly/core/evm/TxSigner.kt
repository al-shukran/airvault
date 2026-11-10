package app.lensly.core.evm

import app.lensly.core.ur.DataType
import java.math.BigInteger
import org.web3j.crypto.Hash

/** Signs unsigned transactions exactly as the wallet sent them. */
object TxSigner {

    /** Outcome of [sign]. Refusals are returned, not thrown. */
    sealed interface Result {
        /** r (32 bytes) || s (32 bytes) || v. */
        class Signed(val signature: ByteArray) : Result

        class Refused(val reason: String) : Result
    }

    /**
     * Signs keccak256 of [signData], the exact bytes the wallet sent. Legacy (dataType 1) uses
     * v = chainId * 2 + 35 + recId as minimal big-endian bytes, so v is longer than one byte for real chain IDs
     * (Monad testnet, 10143, gives two bytes). EIP-1559 (dataType 4) uses v = recId, one byte of 0 or 1.
     * Any other dataType is refused.
     */
    fun sign(signData: ByteArray, dataType: DataType, privateKey: ByteArray): Result {
        val tx = when (val decoded = UnsignedTx.decode(signData, dataType)) {
            is UnsignedTx.Result.Ok -> decoded.tx
            is UnsignedTx.Result.Rejected -> return Result.Refused(decoded.reason)
        }
        val signature = Secp256k1.sign(Hash.sha3(signData), privateKey)
        val v = when (tx.type) {
            TxType.LEGACY_EIP155 -> BigInteger.valueOf(signature.recId.toLong())
                .add(BigInteger.valueOf(35))
                .add(tx.chainId.shiftLeft(1))
                .toMinimalBigEndian()
            TxType.EIP1559 -> byteArrayOf(signature.recId.toByte())
        }
        return Result.Signed(signature.r + signature.s + v)
    }

    private fun BigInteger.toMinimalBigEndian(): ByteArray {
        val bytes = toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }
}
