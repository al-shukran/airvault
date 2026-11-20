package app.lensly.core.evm

import app.airvault.core.signer.EcdsaSignature
import org.web3j.crypto.Sign

/** Signs personal messages (EIP-191, personal_sign). */
object MessageSigner {

    /**
     * The signature of [message] as r (32 bytes) || s (32 bytes) || v, with v of 27 or 28. The EIP-191 hash, keccak256("\u0019Ethereum Signed
     * Message:\n" + length + message), is signed by [signDigest], so a key the signer does not hold signs through it.
     */
    fun signatureOf(message: ByteArray, signDigest: (ByteArray) -> EcdsaSignature): ByteArray {
        val signature = signDigest(Sign.getEthereumMessageHash(message))
        return signature.r + signature.s + byteArrayOf((27 + signature.recoveryId).toByte())
    }
}
