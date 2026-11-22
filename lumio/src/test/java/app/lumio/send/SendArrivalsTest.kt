package app.lumio.send

import app.lumio.btc.EsploraTxStatus
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.AlgorandPendingInfo
import app.lumio.data.provider.EvmReceipt
import app.lumio.data.provider.SolanaStatus
import java.io.IOException
import kotlinx.coroutines.flow.StateFlow
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

/** The arrival watch: when a sent transaction turns arrived, how often the chain is read, and when the notice posts. Time is virtual. */
class SendArrivalsTest {
    private val network = NetworkId(Networks.evm(10143L), testnet = true)

    private class FakeNotice(private val permitted: Boolean) : ArrivalNotice {
        val posted = ArrayList<Triple<String, String, String>>()

        override fun canPost(): Boolean = permitted

        override fun post(id: String, title: String, text: String) {
            posted += Triple(id, title, text)
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
    ): StateFlow<Boolean> = SendArrivals(backgroundScope, notice, foreground = { foreground })
        .watch(network, "tx", "Your MON Has Arrived", "0.25 MON to FelixStone", answersOf(*steps))

    @Test
    fun aSendArrivesOnTheReadThatLands() = runTest {
        val arrived = watchOf(Landing.PENDING, Landing.PENDING, Landing.LANDED)

        runCurrent()
        advanceTimeBy(5_999)
        runCurrent()
        assertFalse(arrived.value)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(arrived.value)
    }

    @Test
    fun readsBackOffFromTwoSecondsToOneMinute() = runTest {
        val readAt = ArrayList<Long>()
        SendArrivals(backgroundScope, FakeNotice(permitted = true), foreground = { true })
            .watch(network, "tx", "Your MON Has Arrived", "0.25 MON", suspend {
                readAt += currentTime
                Landing.PENDING
            })

        advanceTimeBy(200_000)
        runCurrent()

        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L, 30_000L, 62_000L, 122_000L, 182_000L), readAt)
    }

    @Test
    fun aRefusedSendIsNotReadAgain() = runTest {
        var reads = 0
        val arrived = SendArrivals(backgroundScope, FakeNotice(permitted = true), foreground = { true })
            .watch(network, "tx", "Your MON Has Arrived", "0.25 MON") {
                reads++
                Landing.REFUSED
            }

        advanceTimeBy(100_000)
        runCurrent()

        assertEquals(1, reads)
        assertFalse(arrived.value)
    }

    @Test
    fun aFailedReadCountsAsPendingAndIsReadAgain() = runTest {
        var reads = 0
        val arrived = SendArrivals(backgroundScope, FakeNotice(permitted = true), foreground = { true })
            .watch(network, "tx", "Your MON Has Arrived", "0.25 MON") {
                reads++
                if (reads == 1) throw IOException("unreachable")
                Landing.LANDED
            }

        runCurrent()
        assertFalse(arrived.value)

        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(arrived.value)
    }

    @Test
    fun postsTheNoticeWhenTheSendLandsWhileTheAppIsInTheBackground() = runTest {
        val notice = FakeNotice(permitted = true)
        val arrived = watchOf(Landing.LANDED, foreground = false, notice = notice)

        runCurrent()

        assertTrue(arrived.value)
        assertEquals(listOf(Triple("tx", "Your MON Has Arrived", "0.25 MON to FelixStone")), notice.posted)
    }

    @Test
    fun staysQuietWhileTheAppIsOnScreen() = runTest {
        val notice = FakeNotice(permitted = true)
        val arrived = watchOf(Landing.LANDED, foreground = true, notice = notice)

        runCurrent()

        assertTrue(arrived.value)
        assertTrue(notice.posted.isEmpty())
    }

    @Test
    fun neverPostsWithoutTheOwnersPermission() = runTest {
        val notice = FakeNotice(permitted = false)
        val arrived = watchOf(Landing.LANDED, foreground = false, notice = notice)

        runCurrent()

        assertTrue(arrived.value)
        assertTrue(notice.posted.isEmpty())
    }

    @Test
    fun aWatchKeepsItsStateAfterItsCallerLetsGo() = runTest {
        val arrivals = SendArrivals(backgroundScope, FakeNotice(permitted = true), foreground = { false })
        val first = arrivals.watch(network, "tx", "Your MON Has Arrived", "0.25 MON", answersOf(Landing.PENDING, Landing.LANDED))

        advanceTimeBy(2_000)
        runCurrent()
        val later = arrivals.watch(network, "tx", "Your MON Has Arrived", "0.25 MON") { error("the watch is already running") }

        assertSame(first, later)
        assertTrue(later.value)
    }

    @Test
    fun evmArrivesWhenTheReceiptSucceeds() {
        assertEquals(Landing.PENDING, evmLanding(null))
        assertEquals(Landing.LANDED, evmLanding(EvmReceipt(success = true)))
        assertEquals(Landing.REFUSED, evmLanding(EvmReceipt(success = false)))
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
        assertEquals(Landing.PENDING, solanaLanding(null))
        assertEquals(Landing.PENDING, solanaLanding(SolanaStatus(confirmed = false, failed = false)))
        assertEquals(Landing.LANDED, solanaLanding(SolanaStatus(confirmed = true, failed = false)))
        assertEquals(Landing.REFUSED, solanaLanding(SolanaStatus(confirmed = true, failed = true)))
    }

    @Test
    fun algorandArrivesOnAConfirmedRoundAndRefusesAPoolError() {
        assertEquals(Landing.PENDING, algorandLanding(null))
        assertEquals(Landing.PENDING, algorandLanding(AlgorandPendingInfo(confirmedRound = 0L, poolError = "")))
        assertEquals(Landing.LANDED, algorandLanding(AlgorandPendingInfo(confirmedRound = 12L, poolError = "")))
        assertEquals(Landing.REFUSED, algorandLanding(AlgorandPendingInfo(confirmedRound = 0L, poolError = "overspend")))
    }
}
