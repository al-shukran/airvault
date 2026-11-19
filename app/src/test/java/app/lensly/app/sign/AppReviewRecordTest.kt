package app.lensly.app.sign

import app.airvault.chains.algorand.AlgoAddress
import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.AlgoSignRequestEncoder
import app.airvault.chains.algorand.AlgoSignType
import app.airvault.chains.algorand.algorandManifest
import app.airvault.chains.algorand.bytesToSign
import app.airvault.chains.algorand.signer.AlgorandRequestCheck
import app.airvault.chains.algorand.signer.toReview
import app.airvault.core.model.Danger
import app.airvault.core.model.Warning
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.app.SigningAccount
import app.lensly.app.export.partsOf
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.UrScanner
import app.lensly.core.wallet.toHex
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** The Review Record of an Algorand request, built from the review the owner saw: the account that signs, its rows, its network and the checks ticked. */
class AppReviewRecordTest {

    @Test
    fun aSignedPaymentRecordsItsAccountRowsNetworkChecksAndHash() {
        val review = assertIs<AlgorandRequestCheck.Result.Ok>(AlgorandRequestCheck.check(PAY_BASIC, PATH, REQUEST_ID, listOf(SENDER_KEY))).review
        val reviewed = AppPrepared.Ready(
            walletId = WALLET,
            request = appRequest(),
            review = review.toReview(),
            accountIndex = review.accountIndex,
            signer = SigningAccount(name = "Test 0", walletName = "Test", address = SENDER_ADDRESS),
            addressChain = algorandManifest.networks.first { !it.testnet }.chainId,
            counterparties = emptyList(),
            addressNotes = emptyMap(),
        )

        val record = appReviewRecordOf(
            AppReviewEntry(WALLET, ReviewOutcome.SIGNED, appRequest(), reviewed, acknowledged = listOf("Check A"), refusal = null),
            atMs = 1L,
            origin = "lumio",
            dataHash = sha256Hex(PAY_BASIC),
            displayName = "Algorand",
        )

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
        assertEquals(listOf("Check A"), record.acknowledged)
        assertEquals(sha256Hex(PAY_BASIC), record.dataHash)
    }

    @Test
    fun aRecordKeepsTheWarningsTheReviewShowed() {
        val review = assertIs<AlgorandRequestCheck.Result.Ok>(AlgorandRequestCheck.check(PAY_BASIC, PATH, REQUEST_ID, listOf(SENDER_KEY))).review
        val reviewed = AppPrepared.Ready(
            walletId = WALLET,
            request = appRequest(),
            review = review.toReview().copy(warnings = listOf(Warning(Danger.CAUTION, "test.note", "Unknown asset", acknowledge = "Check A"))),
            accountIndex = review.accountIndex,
            signer = SigningAccount(name = "Test 0", walletName = "Test", address = SENDER_ADDRESS),
            addressChain = algorandManifest.networks.first { !it.testnet }.chainId,
            counterparties = emptyList(),
            addressNotes = emptyMap(),
        )

        val record = appReviewRecordOf(
            AppReviewEntry(WALLET, ReviewOutcome.SIGNED, appRequest(), reviewed, acknowledged = listOf("Check A"), refusal = null),
            atMs = 1L,
            origin = "lumio",
            dataHash = sha256Hex(PAY_BASIC),
            displayName = "Algorand",
        )

        assertEquals(listOf(Danger.CAUTION to "Unknown asset"), record.warnings)
    }

    @Test
    fun aRefusalBeforeAReviewRecordsTheReasonWithoutAnAccount() {
        val record = appReviewRecordOf(
            AppReviewEntry(WALLET, ReviewOutcome.REFUSED, appRequest(), reviewed = null, acknowledged = emptyList(), refusal = AlgorandRequestCheck.UNKNOWN_ACCOUNT),
            atMs = 1L,
            origin = "lumio",
            dataHash = sha256Hex(PAY_BASIC),
            displayName = "Algorand",
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
        val message = AppRequest(header(RequestKind.MESSAGE), PAY_BASIC)

        val record = appReviewRecordOf(
            AppReviewEntry(WALLET, ReviewOutcome.REFUSED, message, reviewed = null, acknowledged = emptyList(), refusal = AlgorandRequestCheck.NOT_A_TRANSACTION),
            atMs = 1L,
            origin = "lumio",
            dataHash = sha256Hex(PAY_BASIC),
            displayName = "Algorand",
        )

        assertEquals(ReviewKind.MESSAGE, record.kind)
    }

    private fun header(kind: RequestKind): RequestHeader = RequestHeader(
        app = algorandManifest.id,
        chain = algorandManifest.networks.first { it.testnet }.chainId,
        kind = kind,
        signers = listOf(SignerRef(null, PATH)),
        requestId = REQUEST_ID,
    )

    private fun appRequest(): AppRequest = AppRequest(header(RequestKind.TRANSACTION), PAY_BASIC)

    @Test
    fun anAlgorandRecordHashesTheTransactionTheOwnerSawNotTheTaggedBytesTheSignatureCovers() {
        val scanner = UrScanner()
        partsOf(
            AlgoSignRequestEncoder.encode(
                AlgoSignRequest(
                    requestId = REQUEST_ID,
                    signData = PAY_BASIC,
                    derivationPath = PATH,
                    sourceFingerprint = 0x1234_5678,
                    address = AlgoAddress.encode(SENDER_KEY),
                    origin = "test-origin",
                    signType = AlgoSignType.TRANSACTION,
                ),
            ),
        ).forEach { assertTrue(scanner.receive(it)) }
        val body = checkNotNull(scanner.result()).cborBytes

        val (origin, dataHash) = appRequestFactsOf(AppRequest(header(RequestKind.TRANSACTION), body))

        assertEquals("test-origin", origin)
        assertEquals(sha256Hex(PAY_BASIC), dataHash)
        assertNotEquals(sha256Hex(bytesToSign(PAY_BASIC)), dataHash)
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

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
