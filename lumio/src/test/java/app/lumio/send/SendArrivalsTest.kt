package app.lumio.send

import app.lumio.btc.EsploraTxStatus
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.AlgorandIndexed
import app.lumio.data.provider.AlgorandPendingInfo
import app.lumio.data.provider.EvmReceipt
import app.lumio.data.provider.SolanaStatus
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arrival watch: where a sent transaction stands, how often the chain is read, and when the notice posts. Time is virtual. */
class SendArrivalsTest {
    private val network = NetworkId(Networks.evm(10143L), testnet = true)

    private class FakeNotice(private val permitted: Boolean) : ArrivalNotice {
        val posted = ArrayList<String>()

        override fun canPost(): Boolean = permitted

        override fun post(id: String) {
            posted += id
        }
    }

    /** A chain that answers [steps] one read at a time, then keeps its last answer. */
    private fun answersOf(vararg steps: Landing): suspend () -> Landing {
        var read = 0
        return { steps[minOf(read++, steps.lastIndex)] }
    }

    private fun TestScope.watchOf(
        vararg steps: Landing,
        foreground: Boolean = true,
        notice: FakeNotice = FakeNotice(permitted = true),
    ): StateFlow<Landing> = SendArrivals(backgroundScope, notice, foreground = { foreground })
        .watch(network, "tx", notify = true, answersOf(*steps))

    @Test
    fun aSendArrivesOnTheReadThatLands() = runTest {
        val landing = watchOf(Landing.PENDING, Landing.PENDING, Landing.LANDED)

        runCurrent()
        advanceTimeBy(5_999)
        runCurrent()
        assertEquals(Landing.PENDING, landing.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(Landing.LANDED, landing.value)
    }

    @Test
    fun readsBackOffFromTwoSecondsToOneMinute() = runTest {
        val readAt = ArrayList<Long>()
        SendArrivals(backgroundScope, FakeNotice(permitted = true), foreground = { true })
            .watch(network, "tx", notify = true, suspend {
                readAt += currentTime
                Landing.PENDING
            })

        advanceTimeBy(200_000)
        runCurrent()

        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L, 30_000L, 62_000L, 122_000L, 182_000L), readAt)
    }

    @Test
    fun aRefusedSendEndsRefusedAndIsNotReadAgain() = runTest {
        var reads = 0
        val landing = SendArrivals(backgroundScope, FakeNotice(permitted = true), foreground = { true })
            .watch(network, "tx", notify = true) {
                reads++
                Landing.REFUSED
            }

        advanceTimeBy(100_000)
        runCurrent()

        assertEquals(1, reads)
        assertEquals(Landing.REFUSED, landing.value)
    }

    @Test
    fun anAlgorandSendTheNetworkDroppedEndsRefusedInsteadOfTrackingForever() = runTest {
        val lastValid = 100UL
        var reads = 0
        val landing = SendArrivals(backgroundScope, FakeNotice(permitted = true), foreground = { true })
            .watch(network, "tx", notify = true) {
                reads++
                // The pool lets go and the indexer has read past the last valid round without the transaction.
                algorandLanding(pool = null, indexed = AlgorandIndexed(confirmedRound = null, indexerRound = 101L), lastValid = lastValid)
            }

        runCurrent()
        advanceTimeBy(100_000)
        runCurrent()

        assertEquals(1, reads)
        assertEquals(Landing.REFUSED, landing.value)
    }

    @Test
    fun aFailedReadCountsAsPendingAndIsReadAgain() = runTest {
        var reads = 0
        val landing = SendArrivals(backgroundScope, FakeNotice(permitted = true), foreground = { true })
            .watch(network, "tx", notify = true) {
                reads++
                if (reads == 1) throw IOException("unreachable")
                Landing.LANDED
            }

        runCurrent()
        assertEquals(Landing.PENDING, landing.value)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(Landing.LANDED, landing.value)
    }

    @Test
    fun oneSendHasOnePollLoopHoweverManyPagesWatchIt() = runTest {
        val readAt = ArrayList<Long>()
        val arrivals = SendArrivals(backgroundScope, FakeNotice(permitted = true), foreground = { true })
        val read: suspend () -> Landing = {
            readAt += currentTime
            Landing.PENDING
        }

        val first = arrivals.watch(network, "tx", notify = true, read)
        advanceTimeBy(1_000)
        runCurrent()
        val second = arrivals.watch(network, "tx", notify = true, read)
        advanceTimeBy(199_000)
        runCurrent()

        assertSame(first, second)
        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L, 30_000L, 62_000L, 122_000L, 182_000L), readAt)
    }

    @Test
    fun postsTheNoticeWhenTheSendLandsWhileTheAppIsInTheBackground() = runTest {
        val notice = FakeNotice(permitted = true)
        val landing = watchOf(Landing.LANDED, foreground = false, notice = notice)

        runCurrent()

        assertEquals(Landing.LANDED, landing.value)
        assertEquals(listOf("tx"), notice.posted)
    }

    @Test
    fun aWatchEndedWhileItsReadLandsPostsNoNotice() = runTest {
        val notice = FakeNotice(permitted = true)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        SendArrivals(scope, notice, foreground = { false }).watch(network, "tx", notify = true) {
            scope.cancel()
            Landing.LANDED
        }

        runCurrent()

        assertTrue(notice.posted.isEmpty())
    }

    @Test
    fun staysQuietWhileTheAppIsOnScreen() = runTest {
        val notice = FakeNotice(permitted = true)
        val landing = watchOf(Landing.LANDED, foreground = true, notice = notice)

        runCurrent()

        assertEquals(Landing.LANDED, landing.value)
        assertTrue(notice.posted.isEmpty())
    }

    @Test
    fun neverPostsWithoutTheOwnersPermission() = runTest {
        val notice = FakeNotice(permitted = false)
        val landing = watchOf(Landing.LANDED, foreground = false, notice = notice)

        runCurrent()

        assertEquals(Landing.LANDED, landing.value)
        assertTrue(notice.posted.isEmpty())
    }

    @Test
    fun aWatchWithoutWordsLandsSilently() = runTest {
        val notice = FakeNotice(permitted = true)
        val landing = SendArrivals(backgroundScope, notice, foreground = { false })
            .watch(network, "tx", notify = false, answersOf(Landing.LANDED))

        runCurrent()

        assertEquals(Landing.LANDED, landing.value)
        assertTrue(notice.posted.isEmpty())
    }

    @Test
    fun aWatchKeepsItsStateAfterItsCallerLetsGo() = runTest {
        val arrivals = SendArrivals(backgroundScope, FakeNotice(permitted = true), foreground = { false })
        val first = arrivals.watch(network, "tx", notify = true, answersOf(Landing.PENDING, Landing.LANDED))

        advanceTimeBy(2_000)
        runCurrent()
        val later = arrivals.watch(network, "tx", notify = true) { error("the watch is already running") }

        assertSame(first, later)
        assertEquals(Landing.LANDED, later.value)
    }

    @Test
    fun landingNowReadsTheWatchAndIsPendingWithoutOne() = runTest {
        val arrivals = SendArrivals(backgroundScope, FakeNotice(permitted = true), foreground = { true })
        arrivals.watch(network, "tx", notify = false, answersOf(Landing.REFUSED))

        runCurrent()

        assertEquals(Landing.REFUSED, arrivals.landingNow(network, "tx"))
        assertEquals(Landing.PENDING, arrivals.landingNow(network, "other"))
    }

    @Test
    fun evmArrivesWhenTheReceiptSucceeds() {
        assertEquals(Landing.LANDED, evmLanding(EvmReceipt(success = true), nonceUsed = false))
        assertEquals(Landing.REFUSED, evmLanding(EvmReceipt(success = false), nonceUsed = false))
    }

    @Test
    fun evmIsPendingUntilItsNonceIsUsedWithoutAReceipt() {
        assertEquals(Landing.PENDING, evmLanding(null, nonceUsed = false))
        assertEquals(Landing.REFUSED, evmLanding(null, nonceUsed = true))
    }

    @Test
    fun aReceiptDecidesEvenWhenTheNonceHasMoved() {
        assertEquals(Landing.LANDED, evmLanding(EvmReceipt(success = true), nonceUsed = true))
    }

    @Test
    fun bitcoinArrivesOnTheFirstConfirmation() {
        val unconfirmed = EsploraTxStatus("tx", confirmed = false, blockHeight = null, blockHash = null, blockTimeSec = null)
        val confirmed = EsploraTxStatus("tx", confirmed = true, blockHeight = 100L, blockHash = "hash", blockTimeSec = 1L)

        assertEquals(Landing.PENDING, bitcoinLanding(unconfirmed))
        assertEquals(Landing.LANDED, bitcoinLanding(confirmed))
    }

    @Test
    fun solanaArrivesWhenConfirmedAndRefusesAFailedStatus() {
        assertEquals(Landing.PENDING, solanaLanding(SolanaStatus(confirmed = false, failed = false), dropped = false))
        assertEquals(Landing.LANDED, solanaLanding(SolanaStatus(confirmed = true, failed = false), dropped = false))
        assertEquals(Landing.REFUSED, solanaLanding(SolanaStatus(confirmed = true, failed = true), dropped = false))
    }

    @Test
    fun solanaWithoutAStatusIsPendingUntilTheNetworkCanNoLongerIncludeIt() {
        assertEquals(Landing.PENDING, solanaLanding(null, dropped = false))
        assertEquals(Landing.REFUSED, solanaLanding(null, dropped = true))
    }

    @Test
    fun aStatusDecidesEvenWhenTheTransactionCountsAsDropped() {
        assertEquals(Landing.LANDED, solanaLanding(SolanaStatus(confirmed = true, failed = false), dropped = true))
        assertEquals(Landing.PENDING, solanaLanding(SolanaStatus(confirmed = false, failed = false), dropped = true))
    }

    @Test
    fun algorandWhileThePoolHoldsTheTransactionTheAnswerDecides() {
        val valid = 100UL

        assertEquals(Landing.PENDING, algorandLanding(AlgorandPendingInfo(confirmedRound = 0L, poolError = ""), null, valid))
        assertEquals(Landing.LANDED, algorandLanding(AlgorandPendingInfo(confirmedRound = 12L, poolError = ""), null, valid))
        assertEquals(Landing.REFUSED, algorandLanding(AlgorandPendingInfo(confirmedRound = 0L, poolError = "overspend"), null, valid))
    }

    @Test
    fun algorandOutOfThePoolLandsWhenTheIndexerHoldsIt() {
        val confirmed = AlgorandIndexed(confirmedRound = 90L, indexerRound = 120L)

        assertEquals(Landing.LANDED, algorandLanding(pool = null, indexed = confirmed, lastValid = 100UL))
    }

    @Test
    fun algorandOutOfThePoolIsPendingUntilTheIndexerPassesTheLastValidRound() {
        val behind = AlgorandIndexed(confirmedRound = null, indexerRound = 99L)

        assertEquals(Landing.PENDING, algorandLanding(pool = null, indexed = behind, lastValid = 100UL))
    }

    @Test
    fun algorandOutOfThePoolIsDroppedOnceTheIndexerPassesTheLastValidRound() {
        val dropped = AlgorandIndexed(confirmedRound = null, indexerRound = 100L)

        assertEquals(Landing.REFUSED, algorandLanding(pool = null, indexed = dropped, lastValid = 100UL))
    }

    @Test
    fun algorandWithNoReadOfTheIndexerIsPending() {
        assertEquals(Landing.PENDING, algorandLanding(pool = null, indexed = null, lastValid = 100UL))
    }
}
