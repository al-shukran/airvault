package app.lensly.core.evm

import app.airvault.chains.evm.signer.TypedData
import app.airvault.core.signer.EcdsaSignature

/** Signs typed data (EIP-712) over its digest, never over the JSON text. */
object TypedDataSigner {

    /**
     * The signature of [typed] as r (32 bytes) || s (32 bytes) || v, with v of 27 or 28. The EIP-712 digest is signed by [signDigest], so a key the
     * signer does not hold signs through it. The domain the review showed is checked by the chain app before this is called.
     */
    fun signatureOf(typed: TypedData, signDigest: (ByteArray) -> EcdsaSignature): ByteArray {
        val signature = signDigest(typed.digest())
        return signature.r + signature.s + byteArrayOf((27 + signature.recoveryId).toByte())
    }
}
