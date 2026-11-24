package app.lumio.pair

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The first sync's transitions, with fake reads: every row succeeds, one chain fails, a read throws, and a read runs past 8 s. */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstSyncTest {

    @Test
    fun aRowExistsOnlyForAReadThatTheWalletNeeds() {
        val sync = FirstSync(listOf(SyncStep(listOf(SyncRow.PRICES)) { emptySet() }))
        assertEquals(listOf(SyncRow.PRICES), sync.rows.value.keys.toList())
        assertEquals(mapOf(SyncRow.PRICES to SyncState.Queued), sync.rows.value)
    }

    @Test
    fun everyRowThatSucceedsIsDoneAndTheSyncSettles() = runTest {
        val sync = FirstSync(
            listOf(
                SyncStep(listOf(SyncRow.BITCOIN)) { emptySet() },
                SyncStep(listOf(SyncRow.EVM)) { emptySet() },
                SyncStep(listOf(SyncRow.SOLANA, SyncRow.ALGORAND)) { emptySet() },
                SyncStep(listOf(SyncRow.PRICES)) { emptySet() },
            ),
        )
        assertFalse(FirstSync.isSettled(sync.rows.value))
        sync.run()
        assertEquals(SyncRow.entries.associateWith { SyncState.Done }, sync.rows.value)
        assertTrue(FirstSync.isSettled(sync.rows.value))
    }

    @Test
    fun aFailedChainShowsItsRowAndDoesNotStopTheRest() = runTest {
        val sync = FirstSync(
            listOf(
                SyncStep(listOf(SyncRow.BITCOIN)) { emptySet() },
                SyncStep(listOf(SyncRow.EVM)) { setOf(SyncRow.EVM) },
                SyncStep(listOf(SyncRow.SOLANA, SyncRow.ALGORAND)) { setOf(SyncRow.SOLANA) },
                SyncStep(listOf(SyncRow.PRICES)) { emptySet() },
            ),
        )
        sync.run()
        assertEquals(
            mapOf(
                SyncRow.BITCOIN to SyncState.Done,
                SyncRow.EVM to SyncState.Failed,
                SyncRow.SOLANA to SyncState.Failed,
                SyncRow.ALGORAND to SyncState.Done,
                SyncRow.PRICES to SyncState.Done,
            ),
            sync.rows.value,
        )
        assertTrue(FirstSync.isSettled(sync.rows.value))
    }

    @Test
    fun aReadThatThrowsFailsItsRowsAndTheReadsAfterItRun() = runTest {
        val sync = FirstSync(
            listOf(
                SyncStep(listOf(SyncRow.EVM)) { error("the node did not answer") },
                SyncStep(listOf(SyncRow.PRICES)) { emptySet() },
            ),
        )
        sync.run()
        assertEquals(mapOf(SyncRow.EVM to SyncState.Failed, SyncRow.PRICES to SyncState.Done), sync.rows.value)
    }

    @Test
    fun aReadThatRunsPastEightSecondsSaysItIsSlow() = runTest {
        val sync = FirstSync(listOf(SyncStep(listOf(SyncRow.SOLANA)) { delay(9_000); emptySet() }))
        val job = launch { sync.run() }
        runCurrent()
        assertEquals(SyncState.Working(slow = false), sync.rows.value.getValue(SyncRow.SOLANA))
        advanceTimeBy(FirstSync.SLOW_AFTER_MS - 1)
        runCurrent()
        assertEquals(SyncState.Working(slow = false), sync.rows.value.getValue(SyncRow.SOLANA))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(SyncState.Working(slow = true), sync.rows.value.getValue(SyncRow.SOLANA))
        job.join()
        assertEquals(SyncState.Done, sync.rows.value.getValue(SyncRow.SOLANA))
    }

    @Test
    fun aSecondRunDoesNotReadAgain() = runTest {
        var reads = 0
        val sync = FirstSync(listOf(SyncStep(listOf(SyncRow.PRICES)) { reads++; emptySet() }))
        sync.run()
        sync.run()
        assertEquals(1, reads)
        assertEquals(SyncState.Done, sync.rows.value.getValue(SyncRow.PRICES))
    }

    @Test
    fun aRunThatIsStillGoingIsNotStartedAgain() = runTest {
        var reads = 0
        val sync = FirstSync(listOf(SyncStep(listOf(SyncRow.PRICES)) { reads++; delay(1_000); emptySet() }))
        val first = launch { sync.run() }
        runCurrent()
        sync.run()
        first.join()
        assertEquals(1, reads)
    }

    @Test
    fun aNativeFailureMarksOnlyItsChain() = runTest {
        val sync = FirstSync(listOf(SyncStep(listOf(SyncRow.SOLANA, SyncRow.ALGORAND)) { setOf(SyncRow.ALGORAND) }))
        sync.run()
        assertEquals(mapOf(SyncRow.SOLANA to SyncState.Done, SyncRow.ALGORAND to SyncState.Failed), sync.rows.value)
    }
}
