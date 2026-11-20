package app.lensly.core.evm

import app.airvault.core.signer.EcdsaSignature
import java.math.BigInteger
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric

/** secp256k1 signing of an EVM digest with web3j's nonces and low-s form, for a private key the signer holds. */
object Secp256k1 {
    private val curveOrder: BigInteger = Sign.CURVE_PARAMS.n

    /**
     * Signs a 32-byte [hash] with RFC 6979 nonces and canonical low-s, as web3j's ECKeyPair.sign does.
     * The recovery id is found by recovering the public key from each candidate and comparing it with the signer.
     */
    fun sign(hash: ByteArray, privateKey: ByteArray): EcdsaSignature {
        require(hash.size == 32) { "hash must be 32 bytes" }
        require(privateKey.size == 32) { "private key must be 32 bytes" }
        val key = BigInteger(1, privateKey)
        require(key.signum() > 0 && key < curveOrder) { "private key is out of range" }

        val keyPair = ECKeyPair.create(key)
        val signature = keyPair.sign(hash)
        val recoveryId = (0..1).firstOrNull { Sign.recoverFromSignature(it, signature, hash) == keyPair.publicKey }
            ?: error("no recovery id matches the signing key")
        return EcdsaSignature(
            r = Numeric.toBytesPadded(signature.r, 32),
            s = Numeric.toBytesPadded(signature.s, 32),
            recoveryId = recoveryId,
        )
    }
}
