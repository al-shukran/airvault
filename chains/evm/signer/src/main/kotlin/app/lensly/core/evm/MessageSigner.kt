package app.lensly.core.evm

import app.airvault.chains.evm.signer.DataType
import app.airvault.core.signer.EcdsaSignature
import org.web3j.crypto.Sign

/** Signs personal messages (EIP-191, personal_sign). Typed data (dataType 2) is refused for now. */
object MessageSigner {

    /** Outcome of [sign]. Refusals are returned, not thrown. */
    sealed interface Result {
        /** r (32 bytes) || s (32 bytes) || v, with v of 27 or 28. */
        class Signed(val signature: ByteArray) : Result

        class Refused(val reason: String) : Result
    }

    /** Signs the EIP-191 hash of [message], keccak256("\u0019Ethereum Signed Message:\n" + length + message), with [privateKey]. */
    fun sign(dataType: DataType, message: ByteArray, privateKey: ByteArray): Result {
        if (dataType != DataType.PERSONAL_MESSAGE) {
            return Result.Refused("only personal messages (dataType 3) are signed, not dataType ${dataType.wire}")
        }
        return Result.Signed(signatureOf(message) { digest -> Secp256k1.sign(digest, privateKey) })
    }

    /** The signature of [message] in the form [sign] gives it, with the EIP-191 hash signed by [signDigest]. */
    fun signatureOf(message: ByteArray, signDigest: (ByteArray) -> EcdsaSignature): ByteArray {
        val signature = signDigest(Sign.getEthereumMessageHash(message))
        return signature.r + signature.s + byteArrayOf((27 + signature.recoveryId).toByte())
    }
}
