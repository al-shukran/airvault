package app.airvault.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApprovalTest {
    private val chain = ChainId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k")

    private fun review(vararg warnings: Warning) = Review(
        title = "Review",
        chain = chain,
        network = NetworkLabel("Algorand Testnet", testnet = true, detail = null),
        summary = listOf(SummaryPart.Text("Sends 1 ALGO.")),
        facts = emptyList(),
        sections = emptyList(),
        warnings = warnings.toList(),
        coverage = Coverage(decoded = emptySet(), excluded = emptyMap()),
    )

    private val unlimited = Warning(
        Danger.DANGER,
        "evm.unlimited",
        "This approves an unlimited amount.",
        acknowledge = UNLIMITED_CHECK,
    )
    private val unknown = Warning(Danger.CAUTION, "evm.unknown", "This call is unknown.", acknowledge = UNKNOWN_CHECK)

    @Test
    fun aReviewWithNoCheckIsApprovableAtOnce() {
        assertNotNull(Approval.of(review(), emptySet()))
    }

    @Test
    fun aDangerWithACheckCannotBeApprovedUntilItIsTicked() {
        val dangerous = review(unlimited)

        assertNull(Approval.of(dangerous, emptySet()))
        val approval = assertNotNull(Approval.of(dangerous, setOf(UNLIMITED_CHECK)))
        assertEquals(listOf(UNLIMITED_CHECK), approval.acknowledged)
    }

    @Test
    fun aCautionThatNamesACheckIsHeldToItTooAsTheReviewScreenHasAlwaysHeldIt() {
        assertNull(Approval.of(review(unknown), emptySet()))
    }

    @Test
    fun everyCheckTheReviewNamesMustBeTicked() {
        val both = review(unlimited, unknown)

        assertNull(Approval.of(both, setOf(UNLIMITED_CHECK)))
        assertNotNull(Approval.of(both, setOf(UNLIMITED_CHECK, UNKNOWN_CHECK)))
    }

    @Test
    fun twoWarningsSharingACheckAskForOneTick() {
        val twice = review(unlimited, unlimited.copy(message = "A second approval is unlimited."))

        assertEquals(listOf(UNLIMITED_CHECK), twice.checkLabels())
        assertNotNull(Approval.of(twice, setOf(UNLIMITED_CHECK)))
    }

    @Test
    fun aBlockIsNeverApprovableEvenWhenEveryCheckIsTicked() {
        val block = Warning(Danger.BLOCK, "coverage", "This request has fields the app did not show.")

        assertNull(Approval.of(review(unlimited, block), setOf(UNLIMITED_CHECK)))
    }

    @Test
    fun ticksForChecksTheReviewDoesNotNameDoNoHarm() {
        assertNotNull(Approval.of(review(), setOf("I understand something else.")))
    }

    private companion object {
        const val UNLIMITED_CHECK = "I understand this approval is unlimited."
        const val UNKNOWN_CHECK = "I understand this call."
    }
}
