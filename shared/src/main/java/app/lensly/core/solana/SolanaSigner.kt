package app.lensly.core.solana

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Ed25519 for Solana. A Solana signature is the Ed25519 signature of the message bytes as they are, with no prefix and no hash. The
 * private key is the 32-byte SLIP-0010 key of the account ([app.lensly.core.wallet.Slip10Ed25519]). Nothing here keeps a key.
 */
object SolanaSigner {
    /** The size of an Ed25519 private key or public key. */
    const val KEY_BYTES = 32

    /** The size of an Ed25519 signature. */
    const val SIGNATURE_BYTES = 64

    /**
     * The 64-byte signature of [message] by [privateKey]. The caller zeroes [privateKey]. BouncyCastle keeps its own copy of the key,
     * and that copy cannot be zeroed from here.
     */
    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        require(privateKey.size == KEY_BYTES) { "An Ed25519 private key is 32 bytes." }
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /** The 32-byte public key of [privateKey]. The same caveat as [sign] applies to the copy BouncyCastle keeps. */
    fun publicKeyOf(privateKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_BYTES) { "An Ed25519 private key is 32 bytes." }
        return Ed25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
    }

    /** True when [signature] is the Ed25519 signature of [message] by [publicKey]. A malformed key or signature is false. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != KEY_BYTES || signature.size != SIGNATURE_BYTES) return false
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }
}
