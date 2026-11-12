package app.lensly.ui.home

import app.lensly.app.sign.ReviewKind
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ReviewRecord
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.Test

class HomeFormatTest {

    @Test
    fun countedUsesTheSingularForOneAndThePluralOtherwise() {
        assertEquals("0 networks", counted(0, "network"))
        assertEquals("1 network", counted(1, "network"))
        assertEquals("55 networks", counted(55, "network"))
    }

    @Test
    fun relativeAgeReadsTheTimeInWords() {
        assertEquals("Just now", relativeAge(NOW, NOW))
        assertEquals("Just now", relativeAge(NOW - 59_000L, NOW))
        assertEquals("Just now", relativeAge(NOW + MINUTE, NOW), "a time after now reads as just now")
        assertEquals("1 min ago", relativeAge(NOW - MINUTE, NOW))
        assertEquals("59 min ago", relativeAge(NOW - 59 * MINUTE, NOW))
        assertEquals("1 h ago", relativeAge(NOW - HOUR, NOW))
        assertEquals("23 h ago", relativeAge(NOW - 23 * HOUR, NOW))
        assertEquals("Yesterday", relativeAge(NOW - 25 * HOUR, NOW))
    }

    @Test
    fun aSignedRecordNamesItsOutcomeFunctionAndAmount() {
        assertEquals("Signed · transfer · 0.0100 MON", activitySummary(record(ReviewOutcome.SIGNED, function = "transfer", amount = "0.0100 MON")))
        assertEquals("Signed · approve", activitySummary(record(ReviewOutcome.SIGNED, function = "approve")))
    }

    @Test
    fun aRecordWithNoFunctionReadsAsARequestOrAMessage() {
        assertEquals("Cancelled · Request", activitySummary(record(ReviewOutcome.CANCELLED)))
        assertEquals("Signed · Message", activitySummary(record(ReviewOutcome.SIGNED, kind = ReviewKind.MESSAGE)))
    }

    @Test
    fun aRefusalShowsItsOutcomeNotItsReason() {
        val reason = "Chain 999999 is not in the registry, so this request cannot be checked before it is signed."
        val refused = record(ReviewOutcome.REFUSED, function = "transfer", refusal = reason, amount = "1 MON")

        assertEquals("Refused · transfer", activitySummary(refused))
        assertFalse(activitySummary(refused).contains("registry"), "the reason is left to the Activity detail")
    }

    @Test
    fun aRefusalWithNoFunctionIsJustRefused() {
        assertEquals("Refused", activitySummary(record(ReviewOutcome.REFUSED, refusal = "Unknown chain")))
    }

    @Test
    fun theCatalogSequenceInEffectIsTheInstalledOneOnlyWhenItIsHigher() {
        assertEquals(5L, effectiveCatalogSequence(bakedSequence = 5, installedSequence = 3), "an installed sequence below the build's shows the build's")
        assertEquals(5L, effectiveCatalogSequence(bakedSequence = 5, installedSequence = 5), "a tie keeps the build's")
        assertEquals(7L, effectiveCatalogSequence(bakedSequence = 5, installedSequence = 7))
        assertEquals(5L, effectiveCatalogSequence(bakedSequence = 5, installedSequence = null))
        assertEquals(3L, effectiveCatalogSequence(bakedSequence = 0, installedSequence = 3))
        assertNull(effectiveCatalogSequence(bakedSequence = 0, installedSequence = null), "no sequence anywhere reads as Built-in")
    }

    private fun record(
        outcome: ReviewOutcome,
        kind: ReviewKind = ReviewKind.TRANSACTION,
        function: String? = null,
        amount: String? = null,
        refusal: String? = null,
    ): ReviewRecord = ReviewRecord(
        atMs = NOW,
        outcome = outcome,
        kind = kind,
        chainId = 1L,
        networkName = "Ethereum",
        accountIndex = 0,
        address = null,
        function = function,
        to = null,
        amount = amount,
        tokenContract = null,
        fee = emptyList(),
        params = emptyList(),
        origin = null,
        refusal = refusal,
        dataHash = "00",
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
    }
}
