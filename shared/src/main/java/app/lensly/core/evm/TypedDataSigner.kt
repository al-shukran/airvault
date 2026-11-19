package app.lensly.core.evm

import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.TypedData

/** Signs typed data (EIP-712) over its digest, never over the JSON text. */
object TypedDataSigner {

    /** Outcome of [sign]. Refusals are returned, not thrown. */
    sealed interface Result {
        /** r (32 bytes) || s (32 bytes) || v, with v of 27 or 28. */
        class Signed(val signature: ByteArray) : Result

        class Refused(val reason: String) : Result
    }

    /**
     * Signs the digest of [typed] with [privateKey]. The request must be typed data (dataType 2). The domain's chain is not checked against
     * the request's: the review names the chain the domain names, and the owner reads it before signing.
     */
    fun sign(dataType: DataType, typed: TypedData, privateKey: ByteArray): Result {
        if (dataType != DataType.TYPED_DATA) {
            return Result.Refused("only typed data (dataType 2) is signed here, not dataType ${dataType.wire}")
        }
        val signature = Secp256k1.sign(typed.digest(), privateKey)
        return Result.Signed(signature.r + signature.s + byteArrayOf((27 + signature.recId).toByte()))
    }
}
