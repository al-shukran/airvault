package app.lensly.core.evm

import app.lensly.core.ur.DataType
import org.web3j.crypto.Sign

/** Signs personal messages (EIP-191, personal_sign). Typed data (dataType 2) is refused for now. */
object MessageSigner {

    /** Outcome of [sign]. Refusals are returned, not thrown. */
    sealed interface Result {
        /** r (32 bytes) || s (32 bytes) || v, with v of 27 or 28. */
        class Signed(val signature: ByteArray) : Result

        class Refused(val reason: String) : Result
    }

    /** Signs the EIP-191 hash of [message], keccak256("\u0019Ethereum Signed Message:\n" + length + message). */
    fun sign(dataType: DataType, message: ByteArray, privateKey: ByteArray): Result {
        if (dataType != DataType.PERSONAL_MESSAGE) {
            return Result.Refused("only personal messages (dataType 3) are signed, not dataType ${dataType.wire}")
        }
        val signature = Secp256k1.sign(Sign.getEthereumMessageHash(message), privateKey)
        return Result.Signed(signature.r + signature.s + byteArrayOf((27 + signature.recId).toByte()))
    }
}
