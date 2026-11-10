package app.lensly.core.evm

import java.math.BigInteger
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric

/** An ECDSA signature with r and s as 32 big-endian bytes each, and the recovery id (0 or 1). */
internal class RecoverableSignature(val r: ByteArray, val s: ByteArray, val recId: Int)

internal object Secp256k1 {
    private val curveOrder: BigInteger = Sign.CURVE_PARAMS.n

    /**
     * Signs a 32-byte [hash] with RFC 6979 nonces and canonical low-s, as web3j's ECKeyPair.sign does.
     * The recovery id is found by recovering the public key from each candidate and comparing it with the signer.
     */
    fun sign(hash: ByteArray, privateKey: ByteArray): RecoverableSignature {
        require(hash.size == 32) { "hash must be 32 bytes" }
        require(privateKey.size == 32) { "private key must be 32 bytes" }
        val key = BigInteger(1, privateKey)
        require(key.signum() > 0 && key < curveOrder) { "private key is out of range" }

        val keyPair = ECKeyPair.create(key)
        val signature = keyPair.sign(hash)
        val recId = (0..1).firstOrNull { Sign.recoverFromSignature(it, signature, hash) == keyPair.publicKey }
            ?: error("no recovery id matches the signing key")
        return RecoverableSignature(
            r = Numeric.toBytesPadded(signature.r, 32),
            s = Numeric.toBytesPadded(signature.s, 32),
            recId = recId,
        )
    }
}
