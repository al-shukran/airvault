package app.lensly.core.evm

import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.TypedData
import app.airvault.core.signer.EcdsaSignature

/** Signs typed data (EIP-712) over its digest, never over the JSON text. */
object TypedDataSigner {

    /** Outcome of [sign]. Refusals are returned, not thrown. */
    sealed interface Result {
        /** r (32 bytes) || s (32 bytes) || v, with v of 27 or 28. */
        class Signed(val signature: ByteArray) : Result

        class Refused(val reason: String) : Result
    }

    /**
     * Signs the digest of [typed] with [privateKey]. The request must be typed data (dataType 2). [reviewedDomainSeparator] is the domain
     * separator of the domain the review showed, so [typed] must hash with exactly that domain, including its chain ID. Any other domain is refused.
     */
    fun sign(dataType: DataType, typed: TypedData, reviewedDomainSeparator: ByteArray, privateKey: ByteArray): Result {
        if (dataType != DataType.TYPED_DATA) {
            return Result.Refused("only typed data (dataType 2) is signed here, not dataType ${dataType.wire}")
        }
        if (!typed.domainSeparator().contentEquals(reviewedDomainSeparator)) {
            return Result.Refused("The typed data's domain is not the domain the review showed, so it is not signed")
        }
        return Result.Signed(signatureOf(typed) { digest -> Secp256k1.sign(digest, privateKey) })
    }

    /** The signature of [typed] in the form [sign] gives it, with the EIP-712 digest signed by [signDigest]. */
    fun signatureOf(typed: TypedData, signDigest: (ByteArray) -> EcdsaSignature): ByteArray {
        val signature = signDigest(typed.digest())
        return signature.r + signature.s + byteArrayOf((27 + signature.recoveryId).toByte())
    }
}
