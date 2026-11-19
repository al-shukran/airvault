package app.lensly.app.sign

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.AlgoSignType
import app.airvault.chains.algorand.signer.AlgorandRequestCheck
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.toHex
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test

/** The Review Record of an Algorand request: what the review showed, the account that signs, and the hash of the signed bytes. */
class AlgorandReviewRecordTest {

    @Test
    fun aSignedPaymentRecordsItsAccountRowsNetworkAndHash() {
        val request = request(PAY_BASIC)
        val checked = AlgorandRequestCheck.check(PAY_BASIC, PATH, REQUEST_ID, listOf(SENDER_KEY))
        val reviewed = assertIs<AlgorandRequestCheck.Result.Ok>(checked).review

        val record = algorandReviewRecordOf(AlgorandReviewEntry(WALLET, ReviewOutcome.SIGNED, request, reviewed, refusal = null), atMs = 1L)

        assertEquals(ReviewOutcome.SIGNED, record.outcome)
        assertEquals(ReviewKind.TRANSACTION, record.kind)
        assertEquals("Algorand Testnet", record.networkName)
        assertEquals(0, record.accountIndex)
        assertEquals(SENDER_ADDRESS, record.address)
        assertEquals("lumio", record.origin)
        assertNull(record.refusal)
        assertEquals(
            listOf(
                "Sender" to SENDER_ADDRESS,
                "Receiver" to RECEIVER_ADDRESS,
                "Amount" to "1.500000 ALGO",
                "Fee" to "0.001000 ALGO",
                "Valid from Round" to "50000000",
                "Valid until Round" to "50001000",
            ),
            record.params,
        )
        assertEquals(MessageDigest.getInstance("SHA-256").digest(request.signData).toHex(), record.dataHash)
    }

    @Test
    fun aRefusalBeforeAReviewRecordsTheReasonWithoutAnAccount() {
        val request = request(PAY_BASIC)

        val record = algorandReviewRecordOf(
            AlgorandReviewEntry(WALLET, ReviewOutcome.REFUSED, request, reviewed = null, refusal = AlgorandRequestCheck.UNKNOWN_ACCOUNT),
            atMs = 1L,
        )

        assertEquals(ReviewOutcome.REFUSED, record.outcome)
        assertEquals("Algorand", record.networkName)
        assertNull(record.accountIndex)
        assertNull(record.address)
        assertEquals(emptyList(), record.params)
        assertEquals(AlgorandRequestCheck.UNKNOWN_ACCOUNT, record.refusal)
    }

    @Test
    fun aMessageRecordsAsAMessage() {
        val request = AlgoSignRequest(REQUEST_ID, PAY_BASIC, PATH, null, address = "", origin = "lumio", signType = AlgoSignType.MESSAGE)

        val record = algorandReviewRecordOf(
            AlgorandReviewEntry(WALLET, ReviewOutcome.REFUSED, request, reviewed = null, refusal = AlgorandRequestCheck.NOT_A_TRANSACTION),
            atMs = 1L,
        )

        assertEquals(ReviewKind.MESSAGE, record.kind)
    }

    private fun request(signData: ByteArray): AlgoSignRequest = AlgoSignRequest(
        requestId = REQUEST_ID,
        signData = signData,
        derivationPath = PATH,
        sourceFingerprint = null,
        address = "",
        origin = "lumio",
        signType = AlgoSignType.TRANSACTION,
    )

    private companion object {
        const val WALLET = "algorand-wallet"
        const val SENDER_ADDRESS = "OSI2442FE4NVQHRW4RVLUWO5AF43TPIUM7DS4PDGB7EMFPJMMU4KNIN3GQ"
        const val RECEIVER_ADDRESS = "YEO767URJECB5BKVVIH4ZOBCN5EHGHLU5RCQPRBSCPQ3OBPVMFOYGSM47U"
        val REQUEST_ID = ByteArray(16) { 0x11 }
        val PATH = listOf(PathStep(44, true), PathStep(283, true), PathStep(0, true), PathStep(0, false), PathStep(0, false))
        val SENDER_KEY = hex("7491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538")

        /** The `pay_basic` vector of the SDK: 1.5 ALGO from [SENDER_KEY] to [RECEIVER_ADDRESS] on testnet-v1.0. */
        val PAY_BASIC = hex(
            "89a3616d74ce0016e360a3666565cd03e8a26676ce02faf080a367656eac746573746e65742d76312e30a26768c4204863b5" +
                "18a4b3c84ec810f22d4f1081cb0f71f059a7ac20dec62f7f70e5093a22a26c76ce02faf468a3726376c420c11dff7e914904" +
                "1e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd" +
                "1467c72e3c660fc8c2bd2c6538a474797065a3706179",
        )

        fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
