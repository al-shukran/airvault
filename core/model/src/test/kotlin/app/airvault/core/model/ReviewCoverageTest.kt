package app.airvault.core.model

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewCoverageTest {
    private val chain = ChainId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k")
    private val amount = FieldPath("algorand.amount")
    private val lease = FieldPath("algorand.lease")
    private val fee = FieldPath("algorand.fee")

    private fun review(rows: List<ReviewRow>, coverage: Coverage, facts: List<ReviewRow> = emptyList()) = Review(
        title = "Review",
        chain = chain,
        network = NetworkLabel("Algorand Testnet", testnet = true, detail = null),
        summary = listOf(SummaryPart.Text("Sends 1.000000 ALGO.")),
        facts = facts,
        sections = listOf(ReviewSection("Transaction", rows)),
        warnings = emptyList(),
        coverage = coverage,
    )

    private val amountRow = ReviewRow("Amount", DisplayValue.Amount(BigInteger.ONE, 6, "ALGO"), amount)

    @Test
    fun aDecodedFieldNoRowShowsBlocksTheReview() {
        val checked = review(listOf(amountRow), Coverage(setOf(amount, lease), emptyMap())).checked()
        assertEquals(setOf(lease), checked.unaccounted())
        assertEquals(listOf(Danger.BLOCK), checked.warnings.map { it.level })
    }

    @Test
    fun aFieldAnExclusionNamesDoesNotBlock() {
        val coverage = Coverage(setOf(amount, lease), mapOf(lease to "the lease is not part of the summary"))
        val checked = review(listOf(amountRow), coverage).checked()
        assertTrue(checked.unaccounted().isEmpty())
        assertTrue(checked.warnings.isEmpty())
    }

    @Test
    fun aFactCountsAsShown() {
        val feeRow = ReviewRow("Fee", DisplayValue.Amount(BigInteger.ONE, 6, "ALGO"), fee)
        val checked = review(emptyList(), Coverage(setOf(fee), emptyMap()), facts = listOf(feeRow)).checked()
        assertTrue(checked.warnings.isEmpty())
    }

    @Test
    fun aHeadingOrStepDoesNotAccountForADecodedField() {
        // A heading and a step show no decoded field, so a field only they name stays unaccounted and blocks the review.
        val heading = ReviewRow("Transaction", value = null, field = null)
        val step = ReviewRow("Sends the amount", value = null, field = null)
        val checked = review(listOf(heading, step), Coverage(setOf(amount), emptyMap())).checked()
        assertEquals(setOf(amount), checked.unaccounted())
        assertEquals(listOf(Danger.BLOCK), checked.warnings.map { it.level })
    }

    @Test
    fun aDeeperRowAccountsForItsFieldLikeAnyOther() {
        val nested = ReviewRow("Amount", DisplayValue.Amount(BigInteger.ONE, 6, "ALGO"), amount, depth = 2)
        assertTrue(review(listOf(nested), Coverage(setOf(amount), emptyMap())).checked().warnings.isEmpty())
    }

    @Test
    fun valuesShowTheirText() {
        assertEquals("1.500000 ALGO", DisplayValue.Amount(BigInteger("1500000"), 6, "ALGO").text())
        assertEquals("0.000250 USDC", DisplayValue.Amount(BigInteger("250"), 6, "USDC").text())
        assertEquals("250 base units", DisplayValue.Amount(BigInteger("250"), null, null).text())
        assertEquals("0x0001", DisplayValue.Bytes(byteArrayOf(0, 1)).text())
        assertEquals("42", DisplayValue.Number(BigInteger("42")).text())
        assertEquals("hi", DisplayValue.Text("hi").text())
        assertEquals("ABC", DisplayValue.Address(chain, "ABC").text())
        assertEquals("approve me", DisplayValue.Message("approve me").text())
        val absent: DisplayValue? = null
        assertEquals("", absent.text())
    }

    @Test
    fun aDangerWithNoCheckIsCheckedAsABlockKeepingItsMessage() {
        val unnamed = Warning(Danger.DANGER, "evm.unnamed", "This cannot be acknowledged.")
        val named = Warning(Danger.DANGER, "evm.named", "This has a check.", acknowledge = "I understand.")
        val checked = review(listOf(amountRow), Coverage(setOf(amount), emptyMap()))
            .copy(warnings = listOf(unnamed, named))
            .checked()

        assertEquals(listOf(Danger.BLOCK, Danger.DANGER), checked.warnings.map { it.level })
        assertEquals("This cannot be acknowledged.", checked.warnings[0].message)
        assertEquals(listOf("I understand."), checked.checkLabels())
    }

    @Test
    fun aSummaryReadsAsItsPartsWithEachCounterpartyAsItsAddress() {
        val parts = listOf(
            SummaryPart.Text("Approve "),
            SummaryPart.Counterparty("0xSPENDER"),
            SummaryPart.Text(" to spend "),
            SummaryPart.Mono("1.5 USDC"),
        )
        assertEquals("Approve 0xSPENDER to spend 1.5 USDC", parts.text())
    }

    @Test
    fun printableTextRefusesControlCharacters() {
        assertEquals("hello", printableText("hello".toByteArray()))
        assertNull(printableText(byteArrayOf(0, 1)))
    }
}
