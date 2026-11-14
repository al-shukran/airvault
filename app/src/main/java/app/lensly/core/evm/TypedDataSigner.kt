package app.lensly.core.evm

import app.lensly.core.ur.DataType
import java.math.BigInteger

/** Signs typed data (EIP-712) over its digest, never over the JSON text. */
object TypedDataSigner {

    /** Outcome of [sign]. Refusals are returned, not thrown. */
    sealed interface Result {
        /** r (32 bytes) || s (32 bytes) || v, with v of 27 or 28. */
        class Signed(val signature: ByteArray) : Result

        class Refused(val reason: String) : Result
    }

    /**
     * Signs the digest of [typed] with [privateKey]. The request must be typed data (dataType 2), and its [chainId] must be the chain
     * the domain names, so a message is never signed for a chain other than the one the request named.
     */
    fun sign(dataType: DataType, typed: TypedData, chainId: Long, privateKey: ByteArray): Result {
        if (dataType != DataType.TYPED_DATA) {
            return Result.Refused("only typed data (dataType 2) is signed here, not dataType ${dataType.wire}")
        }
        if (typed.domainChainId != BigInteger.valueOf(chainId)) {
            return Result.Refused("The typed data is for another chain than the request names")
        }
        val signature = Secp256k1.sign(typed.digest(), privateKey)
        return Result.Signed(signature.r + signature.s + byteArrayOf((27 + signature.recId).toByte()))
    }
}
