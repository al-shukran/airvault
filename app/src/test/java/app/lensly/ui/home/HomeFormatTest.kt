package app.lensly.ui.home

import app.lensly.app.sign.ReviewKind
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ReviewRecord
import kotlin.test.assertEquals
import org.junit.Test

class HomeFormatTest {

    @Test
    fun theSealedLineCountsWalletsAndAccountsWithSingularAndPlural() {
        assertEquals("Sealed · 0 wallets · 0 accounts", sealedLine(0, 0))
        assertEquals("Sealed · 1 wallet · 1 account", sealedLine(1, 1))
        assertEquals("Sealed · 5 wallets · 19 accounts", sealedLine(5, 19))
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
    fun aSignedRecordNamesItsFunctionAndAmount() {
        assertEquals("transfer · 0.0100 MON", activitySummary(record(ReviewOutcome.SIGNED, function = "transfer", amount = "0.0100 MON")))
        assertEquals("approve", activitySummary(record(ReviewOutcome.SIGNED, function = "approve")))
    }

    @Test
    fun aRecordWithNoFunctionReadsAsARequestOrAMessage() {
        assertEquals("Request", activitySummary(record(ReviewOutcome.CANCELLED)))
        assertEquals("Message", activitySummary(record(ReviewOutcome.SIGNED, kind = ReviewKind.MESSAGE)))
    }

    @Test
    fun aRefusalNamesItsReasonRatherThanTheFunction() {
        val refused = record(ReviewOutcome.REFUSED, function = "transfer", refusal = "Unknown chain", amount = "1 MON")
        assertEquals("Unknown chain", activitySummary(refused))
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
