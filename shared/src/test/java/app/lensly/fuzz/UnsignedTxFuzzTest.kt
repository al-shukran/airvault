package app.lensly.fuzz

import app.lensly.core.evm.UnsignedTx
import app.lensly.core.ur.DataType
import com.code_intelligence.jazzer.junit.FuzzTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * `signData` of a transaction request is attacker-controlled RLP. [UnsignedTx.decode] decodes without a wallet or
 * vault, so it is safe to fuzz directly, and it documents that decoding never throws.
 *
 * Each input is tried as both supported encodings (legacy EIP-155 and typed EIP-1559). The target asserts only the
 * no-throw contract.
 */
class UnsignedTxFuzzTest {

    @MethodSource("seeds")  // Jazzer requires @FuzzTest to be the last annotation on the method.
    @FuzzTest(maxDuration = "15m")
    fun transactionDecodeNeverThrows(bytes: ByteArray) {
        for (dataType in listOf(DataType.TRANSACTION, DataType.TYPED_TRANSACTION)) {
            when (UnsignedTx.decode(bytes, dataType)) {
                is UnsignedTx.Result.Ok -> Unit
                is UnsignedTx.Result.Rejected -> Unit
            }
        }
    }

    companion object {
        @JvmStatic
        fun seeds(): List<ByteArray> = FuzzSeeds.bytes("unsigned-tx")
    }
}
