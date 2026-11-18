package app.lensly.core.algorand

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.AlgoSignType
import app.airvault.chains.algorand.AssetTransfer
import app.airvault.chains.algorand.signer.AlgoTxnDecoder
import app.airvault.chains.algorand.signer.AlgoTxnException
import app.lensly.core.ur.PathStep
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Tests for [AlgoTxnEncoder.encodeAssetTransfer]. Every `axfer` vector from py-algorand-sdk 2.12.0 must decode and encode back to the SDK's bytes,
 * and an opt-in built from the fields of the SDK's opt-in must encode to the SDK's opt-in bytes.
 */
class AlgoAssetTransferEncoderTest {
    @Test
    fun everyAssetTransferFromTheSdkEncodesBackToItsBytes() {
        val transfers = AlgoTxnVectors.valid().filter { it.name.startsWith("axfer") }
        assertTrue(transfers.size >= 10, "the vectors hold the asset transfers")
        for (vector in transfers) {
            val txn = assertIs<AssetTransfer>(AlgoTxnDecoder.decode(vector.raw), vector.name)
            assertContentEquals(vector.raw, AlgoTxnEncoder.encodeAssetTransfer(txn), vector.name)
        }
    }

    @Test
    fun anOptInBuiltFromTheSdkFieldsEncodesToTheSdkOptIn() {
        val vector = AlgoTxnVectors.valid().single { it.name == "axfer_optin" }
        val sdk = assertIs<AssetTransfer>(AlgoTxnDecoder.decode(vector.raw))
        val optIn = AssetTransfer(
            sender = sdk.sender,
            fee = sdk.fee,
            firstValid = sdk.firstValid,
            lastValid = sdk.lastValid,
            genesisId = sdk.genesisId,
            genesisHash = sdk.genesisHash,
            note = sdk.note,
            lease = null,
            group = null,
            rekeyTo = null,
            assetId = sdk.assetId,
            amount = 0UL,
            receiver = sdk.sender,
            closeTo = null,
            assetSender = null,
        )
        assertTrue(optIn.isOptIn)
        assertContentEquals(vector.raw, AlgoTxnEncoder.encodeAssetTransfer(optIn))
    }

    @Test
    fun lenslyReviewsAnOptInThatLumioBuildsAndSignsItsBytes() {
        val vector = AlgoTxnVectors.valid().single { it.name == "axfer_optin" }
        val sdk = assertIs<AssetTransfer>(AlgoTxnDecoder.decode(vector.raw))
        val optIn = AssetTransfer(
            sender = sdk.sender, fee = sdk.fee, firstValid = sdk.firstValid, lastValid = sdk.lastValid, genesisId = sdk.genesisId,
            genesisHash = sdk.genesisHash, note = sdk.note, lease = null, group = null, rekeyTo = null, assetId = sdk.assetId,
            amount = 0UL, receiver = sdk.sender, closeTo = null, assetSender = null,
        )
        val request = AlgoSignRequest(
            requestId = ByteArray(16) { 7 },
            signData = AlgoTxnEncoder.encodeAssetTransfer(optIn),
            derivationPath = listOf(PathStep(44, true), PathStep(283, true), PathStep(0, true), PathStep(0, false), PathStep(0, false)),
            sourceFingerprint = null,
            address = "",
            origin = "lumio",
            signType = AlgoSignType.TRANSACTION,
        )
        val review = assertIs<AlgorandRequestCheck.Result.Ok>(AlgorandRequestCheck.check(request, listOf(sdk.sender))).review
        assertTrue(assertIs<AssetTransfer>(review.txn).isOptIn)
    }

    @Test
    fun anAssetTransferTheDecoderRefusesIsNotEncoded() {
        val txn = AssetTransfer(
            sender = ByteArray(32) { 1 },
            fee = 1_000UL,
            firstValid = 50_000_000UL,
            lastValid = 50_001_000UL,
            genesisId = "testnet-v1.0",
            genesisHash = ByteArray(32) { 2 },
            note = ByteArray(0),
            lease = null,
            group = null,
            rekeyTo = null,
            assetId = 0UL,
            amount = 1UL,
            receiver = ByteArray(32) { 3 },
            closeTo = null,
            assetSender = null,
        )
        val refused = assertIs<AlgoTxnException>(runCatching { AlgoTxnEncoder.encodeAssetTransfer(txn) }.exceptionOrNull())
        assertTrue(refused.message.orEmpty().contains("xaid"), refused.message)
    }
}
