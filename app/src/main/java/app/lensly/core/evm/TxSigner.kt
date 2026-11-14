package app.lensly.core.evm

import app.lensly.core.parse.CANNOT_BE_READ
import java.math.BigInteger
import org.web3j.crypto.Hash

/** Signs unsigned transactions exactly as the wallet sent them. */
object TxSigner {
    /**
     * Signs keccak256 of the payload that [tx] rebuilds from its fields, and returns r (32 bytes) || s (32 bytes) || v. The
     * Parser Sandbox decoded [tx], so it is trusted only as far as the payload matches [signData]: when they differ, nothing
     * is signed. Legacy uses v = chainId * 2 + 35 + recId as minimal big-endian bytes, so v is longer than one byte for real
     * chain IDs (Monad testnet, 10143, gives two bytes). EIP-1559 uses v = recId, one byte of 0 or 1.
     */
    fun sign(signData: ByteArray, tx: UnsignedTx, privateKey: ByteArray): ByteArray {
        val payload = tx.encode()
        require(payload != null && payload.contentEquals(signData)) { CANNOT_BE_READ }
        val signature = Secp256k1.sign(Hash.sha3(payload), privateKey)
        val v = when (tx.type) {
            TxType.LEGACY_EIP155 -> BigInteger.valueOf(signature.recId.toLong())
                .add(BigInteger.valueOf(35))
                .add(tx.chainId.shiftLeft(1))
                .toMinimalBigEndian()
            TxType.EIP1559, TxType.EIP2930 -> byteArrayOf(signature.recId.toByte())
        }
        return signature.r + signature.s + v
    }

    private fun BigInteger.toMinimalBigEndian(): ByteArray {
        val bytes = toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }
}
