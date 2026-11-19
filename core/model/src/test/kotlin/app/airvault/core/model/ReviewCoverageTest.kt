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
        summary = "Sends 1.000000 ALGO.",
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
    fun valuesShowTheirText() {
        assertEquals("1.500000 ALGO", DisplayValue.Amount(BigInteger("1500000"), 6, "ALGO").text())
        assertEquals("0.000250 USDC", DisplayValue.Amount(BigInteger("250"), 6, "USDC").text())
        assertEquals("250 base units", DisplayValue.Amount(BigInteger("250"), null, null).text())
        assertEquals("0x0001", DisplayValue.Bytes(byteArrayOf(0, 1)).text())
        assertEquals("42", DisplayValue.Number(BigInteger("42")).text())
        assertEquals("hi", DisplayValue.Text("hi").text())
        assertEquals("ABC", DisplayValue.Address(chain, "ABC").text())
    }

    @Test
    fun printableTextRefusesControlCharacters() {
        assertEquals("hello", printableText("hello".toByteArray()))
        assertNull(printableText(byteArrayOf(0, 1)))
    }
}
