package app.lensly.e2e

import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.airvault.chains.algorand.AlgoSignType
import app.airvault.chains.algorand.bytesToSign
import app.lensly.app.export.partsOf
import app.airvault.chains.algorand.AlgoAddress
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.ur.AlgoSignature
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.framesOf
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.Ed25519Signer
import app.lensly.ui.flow.SignStep
import com.sparrowwallet.hummingbird.UR
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Algorand signing flow, end to end, on a device: an Algorand account is added to the test wallet, and an `algo-sign-request` for the
 * SDK's `pay_basic` vector, 1.5 ALGO from account 0, is fed to the scanner through the real [app.lensly.ui.flow.SignWalletRoute]. The review
 * shows the amount and the receiver, the owner approves it with the App Password, and the `algo-signature` that comes back is checked as an
 * Ed25519 signature over "TX" and the transaction bytes, by the account key derived from the seed.
 */
@RunWith(AndroidJUnit4::class)
class AlgorandSignFlowTest : SignFlowE2eBase() {

    @Test
    fun algorandPaymentIsReviewedApprovedAndAnsweredWithAVerifyingSignature() {
        val wallet = openWallet { store, id -> store.addAlgorandAccounts(id, 1, BulkCap.DEFAULT.accounts) }
        val signer = AlgorandAccount.publicKey(E2E_SEED, 0)
        val txn = hex(PAY_BASIC)
        val requestId = ByteArray(16) { 0x11 }
        val request = requestCbor(txn, requestId, wallet.fingerprint)

        val driver = driverFor(wallet, partsOf(framesOf(UR("algo-sign-request", request), DEFAULT_MAX_FRAGMENT)))
        driver.open()
        driver.awaitReview { it is SignStep.AppReview }

        // 1,500,000 microAlgos is 1.5 ALGO, sent to the receiver of the vector.
        compose.onNodeWithText("Sends 1.500000 ALGO to ${AlgoAddress.encode(RECEIVER)}.").assertExists()
        driver.readToTheEnd()
        assertTrue(driver.approvable())

        val parts = driver.approveAndSign { it is SignStep.AppConfirming }
        val answer = AlgoSignature.decode(urOfParts(parts, "algo-signature"))
        assertTrue("the signature must decode: $answer", answer is AlgoSignature.Result.Ok)
        val signature = (answer as AlgoSignature.Result.Ok).signature
        assertArrayEquals(requestId, signature.requestId)
        assertArrayEquals("the answer must carry the account key", signer, requireNotNull(signature.publicKey))
        assertTrue(
            "the signature must verify under the account key",
            Ed25519Signer.verify(signer, bytesToSign(txn), signature.signature),
        )
        // A negative control: the signature over any other bytes must fail, so the check above means something.
        assertFalse(Ed25519Signer.verify(signer, bytesToSign(txn + byteArrayOf(0)), signature.signature))
    }

    /** A `algo-sign-request` for the transaction [txn] on account 0 of the wallet with [fingerprint], as a wallet sends it. */
    private fun requestCbor(txn: ByteArray, requestId: ByteArray, fingerprint: Int): ByteArray {
        val path = listOf(44L to true, 283L to true, 0L to true, 0L to false, 0L to false)
        val components = path.flatMap { (index, hardened) -> listOf(Cbor.Uint(index), Cbor.Bool(hardened)) }
        val keypath = Cbor.Tagged(
            304L,
            Cbor.Dict(listOf(Cbor.Uint(1L) to Cbor.Arr(components), Cbor.Uint(2L) to Cbor.Uint(fingerprint.toLong() and 0xFFFFFFFFL))),
        )
        return Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(1L) to Cbor.Tagged(37L, Cbor.Bytes(requestId)),
                    Cbor.Uint(2L) to Cbor.Bytes(txn),
                    Cbor.Uint(3L) to keypath,
                    Cbor.Uint(4L) to Cbor.Text(""),
                    Cbor.Uint(5L) to Cbor.Text("lumio"),
                    Cbor.Uint(6L) to Cbor.Uint(AlgoSignType.TRANSACTION.wire.toLong()),
                ),
            ),
        )
    }

    private companion object {
        /** The receiver of [PAY_BASIC], the 32-byte public key the vector pays. */
        val RECEIVER: ByteArray = hex("c11dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615d")

        /** The `pay_basic` vector of the SDK: 1.5 ALGO from account 0 of the wallet to [RECEIVER], on testnet-v1.0. */
        const val PAY_BASIC =
            "89a3616d74ce0016e360a3666565cd03e8a26676ce02faf080a367656eac746573746e65742d76312e30a26768c4204863b5" +
                "18a4b3c84ec810f22d4f1081cb0f71f059a7ac20dec62f7f70e5093a22a26c76ce02faf468a3726376c420c11dff7e914904" +
                "1e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd" +
                "1467c72e3c660fc8c2bd2c6538a474797065a3706179"

        fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
