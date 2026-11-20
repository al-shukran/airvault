package app.lensly.core.evm

import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.UnsignedTx

/** Outcome of the test [sign] below. */
sealed interface TxSignResult {
    class Signed(val signature: ByteArray) : TxSignResult

    class Refused(val reason: String) : TxSignResult
}

/** Decodes [signData] in this process, as the Parser Sandbox does for the app, then signs what it decoded. */
fun TxSigner.sign(signData: ByteArray, dataType: DataType, privateKey: ByteArray): TxSignResult =
    when (val decoded = UnsignedTx.decode(signData, dataType)) {
        is UnsignedTx.Result.Ok -> TxSignResult.Signed(signatureOf(signData, decoded.tx, EvmFixtures.singleKeySigner(privateKey)))
        is UnsignedTx.Result.Rejected -> TxSignResult.Refused(decoded.reason)
    }
