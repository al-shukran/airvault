package app.lensly.app.sign

import app.airvault.chains.solana.solanaManifest
import app.airvault.core.model.Coverage
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.ReviewSection
import app.airvault.core.model.SummaryPart
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.app.ReviewRecordCodec
import app.lensly.app.SigningAccount
import app.lensly.core.ur.PathStep
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** The Review Record of an installed chain app keeps the facts of its review, and of a Bitcoin review its capped outputs. */
class AppRecordFactsTest {
    @Test
    fun aSolanaRecordKeepsItsClusterAndFeesInTheDetails() {
        val record = signedRecord(review = solanaReview(), accountIndex = 0)

        assertEquals("Solana", record.networkName)
        assertEquals(
            listOf(
                "Network" to "Solana Devnet (stated by Lumio)",
                "Base Fee" to "5000 lamports",
                "Max Fee" to "5100 lamports",
                "Amount" to "1 SOL",
            ),
            record.params,
        )
    }

    @Test
    fun aSignedRecordOfAnAddressKeyHasNoAccountIndexButKeepsItsAddress() {
        val record = signedRecord(review = solanaReview(), accountIndex = null)

        assertEquals(ReviewOutcome.SIGNED, record.outcome)
        assertNull(record.accountIndex)
        assertEquals(ADDRESS, record.address)
    }

    @Test
    fun aBitcoinRecordWithMoreOutputsThanItKeepsStaysUnderTheSlotAndRoundTrips() {
        val facts = BitcoinRecordFacts(
            scriptType = "Native SegWit",
            outputs = List(MAX_RECORD_OUTPUTS) { BitcoinRecordOutput("bc1q" + "x".repeat(58), 1_000L + it, isOwnChange = false) },
            feeSats = 300L,
            feeRateSatPerVb = 2.5,
            notices = emptyList(),
            outputsNotKept = 800,
        )
        val record = ReviewRecord(
            atMs = 1L,
            outcome = ReviewOutcome.SIGNED,
            kind = ReviewKind.BITCOIN_TRANSACTION,
            networkName = "Bitcoin",
            accountIndex = 0,
            address = null,
            function = null,
            to = null,
            amount = null,
            tokenContract = null,
            fee = emptyList(),
            params = emptyList(),
            origin = null,
            refusal = null,
            dataHash = "00",
            bitcoin = facts,
        )

        val bytes = ReviewRecordCodec.encode(record)

        assertTrue(bytes.size < 72 * 1024, "the record is ${bytes.size} bytes")
        assertEquals(facts, ReviewRecordCodec.decode(bytes).bitcoin)
    }

    private fun signedRecord(review: Review, accountIndex: Int?): ReviewRecord {
        val request = AppRequest(
            RequestHeader(
                app = solanaManifest.id,
                chain = SOLANA_DEVNET,
                kind = RequestKind.TRANSACTION,
                signers = listOf(SignerRef(null, PATH)),
                requestId = ByteArray(16) { 0x22 },
            ),
            ByteArray(4),
        )
        val reviewed = AppPrepared.Ready(
            walletId = WALLET,
            request = request,
            review = review,
            accountIndex = accountIndex,
            signer = SigningAccount(name = "Test 0", walletName = "Test", address = ADDRESS),
            addressChain = SOLANA_DEVNET,
            counterparties = emptyList(),
            addressNotes = emptyMap(),
        )
        return appReviewRecordOf(
            AppReviewEntry(WALLET, ReviewOutcome.SIGNED, request, reviewed, acknowledged = listOf("Check A"), refusal = null),
            atMs = 1L,
            origin = null,
            dataHash = "00",
            displayName = "Solana",
        )
    }

    private fun solanaReview(): Review = Review(
        title = "Review",
        chain = SOLANA_DEVNET,
        network = NetworkLabel(
            name = "Solana",
            testnet = true,
            detail = null,
            row = ReviewRow("Network", DisplayValue.Text("Solana Devnet (stated by Lumio)"), field = null),
        ),
        summary = listOf(SummaryPart.Text("Sends 1 SOL.")),
        facts = listOf(
            ReviewRow("Base Fee", DisplayValue.Text("5000 lamports"), field = null),
            ReviewRow("Max Fee", DisplayValue.Text("5100 lamports"), field = null),
        ),
        sections = listOf(ReviewSection("Instructions", listOf(ReviewRow("Amount", DisplayValue.Text("1 SOL"), field = null)))),
        warnings = emptyList(),
        coverage = Coverage(decoded = emptySet(), excluded = emptyMap()),
    )

    private companion object {
        val SOLANA_DEVNET = solanaManifest.networks.first { it.testnet }.chainId
        const val WALLET = "solana-wallet"
        const val ADDRESS = "7EcDhSYGxXyscszYEp35KHN8vvw3svAuLKTzXwCFLtV"
        val PATH = listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true))
    }
}
