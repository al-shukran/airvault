package app.airvault.core.model

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordFactsTest {
    private val chain = ChainId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k")
    private val receiverAddress = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567ABCDEFGHIJKLMNOPQRSTUVWXYZ23"

    private val review = Review(
        title = "Send Algorand",
        chain = chain,
        network = NetworkLabel(
            name = "Algorand Testnet",
            testnet = true,
            detail = "Genesis ID testnet-v1.0",
            row = ReviewRow("Cluster", DisplayValue.Text("testnet"), field = null),
        ),
        summary = listOf(SummaryPart.Text("Sends 1.5 ALGO.")),
        facts = listOf(ReviewRow("Fee", DisplayValue.Amount(BigInteger.valueOf(1000), 6, "ALGO"), FieldPath("algorand.fee"))),
        sections = listOf(
            ReviewSection(
                "Transaction",
                listOf(
                    ReviewRow("Receiver", DisplayValue.Address(chain, receiverAddress), FieldPath("algorand.receiver")),
                    ReviewRow("Amount", DisplayValue.Amount(BigInteger.valueOf(1_500_000), 6, "ALGO"), FieldPath("algorand.amount")),
                    ReviewRow("Lease", value = null, field = null),
                ),
            ),
        ),
        warnings = listOf(
            Warning(Danger.DANGER, "algorand.close-to", "This closes the account.", acknowledge = "I understand the close."),
        ),
        coverage = Coverage(decoded = emptySet(), excluded = emptyMap()),
    )

    @Test
    fun theFactsAndRowsKeepTheirOrderTheirTextAndNoHeadings() {
        val facts = review.recordFacts()

        assertEquals(listOf("Cluster" to "testnet", "Fee" to "0.001000 ALGO"), facts.facts)
        assertEquals(listOf("Receiver" to receiverAddress, "Amount" to "1.500000 ALGO"), facts.rows)
    }

    @Test
    fun everyAddressTheReviewShowsIsKeptInFull() {
        assertEquals(listOf(receiverAddress), review.recordFacts().addresses)
    }

    @Test
    fun theChainNetworkAndTitleAreTheReviewsOwn() {
        val facts = review.recordFacts()

        assertEquals(chain, facts.chain)
        assertEquals(review.network, facts.network)
        assertEquals("Send Algorand", facts.title)
    }

    @Test
    fun theWarningsShownAndTheChecksTickedAreKept() {
        val facts = review.recordFacts(acknowledged = listOf("I understand the close."))

        assertEquals(listOf(Danger.DANGER to "This closes the account."), facts.warnings)
        assertEquals(listOf("I understand the close."), facts.acknowledged)
    }

    @Test
    fun aReviewWithNoChecksTicksNothing() {
        assertEquals(emptyList(), review.copy(warnings = emptyList()).recordFacts().acknowledged)
    }
}
