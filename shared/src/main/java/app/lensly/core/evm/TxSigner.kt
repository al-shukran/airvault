package app.lensly.core.evm

import app.lensly.core.parse.CANNOT_BE_READ
import java.math.BigInteger
import org.web3j.crypto.ECDSASignature
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign

/** Signs unsigned transactions exactly as the wallet sent them. */
object TxSigner {
    private const val SIGNATURE_BYTES = 65

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

    /**
     * The EIP-55 address whose key made [signature] over [signData], recovered from the signature and the payload alone. [signature] is
     * r (32 bytes) || s (32 bytes) || v, with v the recovery id of a typed transaction, 0 or 1. Null when the signature does not recover a
     * key. It holds no key, so a caller that has none can check a signature against the account it expects.
     */
    fun recoverSigner(signData: ByteArray, signature: ByteArray): String? {
        require(signature.size == SIGNATURE_BYTES) { "signature must be r || s || v" }
        val recId = signature[64].toInt()
        if (recId != 0 && recId != 1) return null
        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        val publicKey = try {
            Sign.recoverFromSignature(recId, ECDSASignature(r, s), Hash.sha3(signData))
        } catch (e: RuntimeException) {
            null
        } ?: return null
        return Keys.toChecksumAddress(Keys.getAddress(publicKey))
    }

    private fun BigInteger.toMinimalBigEndian(): ByteArray {
        val bytes = toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }
}
