package app.lumio

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.btc.AddressReading
import app.lumio.btc.remember
import app.lumio.data.db.LumioDatabase
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.sched.Priority
import app.lumio.data.scan.ScanJob
import app.lumio.data.scan.ScanQueue
import app.lumio.data.scan.ScanRunner
import app.lumio.pair.FirstSync
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairedWallets
import app.lumio.pair.SyncRow
import app.lumio.pair.SyncStep
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A lock wipes the vault's attachment, and with it the paired wallets that the session kept, so no read of them outlives the lock. */
@RunWith(RobolectricTestRunner::class)
@Config(application = LumioApp::class)
class LumioVaultWipeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun wipeDropsThePairedWalletsTheSessionKept() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
        val vault = LumioVault(session, LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "wipe.db")))
        PairedWallets.save(session, PairFixtures.payloadOf(listOf(PairFixtures.ethereumAccount(0, "Main"))), nowMs = 100L)
        val listed = PairedWallets.list(session)
        assertTrue("A list keeps the wallets for the session.", PairedWallets.isKept(session))

        vault.wipe()

        assertFalse("A lock drops them.", PairedWallets.isKept(session))
        assertEquals("The next list reads them again.", listed, PairedWallets.list(session))
    }

    @Test
    fun aNewVaultSessionStartsWithAnEmptyAddressMemo() {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
        val database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "memo.db"))
        val net = NetworkId(Networks.BTC_MAINNET, testnet = false)
        val first = LumioVault(session, database)
        first.addressStats.forNetwork(net).remember("bc1qaddress", AddressReading(used = true, confirmedSats = 0L, unconfirmedSats = 0L), nowMs = 1L)
        assertEquals(1, first.addressStats.forNetwork(net).size)

        val second = LumioVault(session, database)

        assertTrue("The next session reads none of the last one's readings.", second.addressStats.forNetwork(net).isEmpty())
    }

    @Test
    fun aLockCancelsAScanPassAndTheNextPassRunsItsJobs() = runBlocking {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
        val vault = LumioVault(session, LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "scan.db")))
        val net = NetworkId(Networks.BTC_MAINNET, testnet = false)
        val queue = ScanQueue { vault.scanRows }
        queue.enqueue(ScanJob(walletId = "wallet-1", net = net, accountRef = "account-a", priority = Priority.FOREGROUND))
        queue.enqueue(ScanJob(walletId = "wallet-1", net = net, accountRef = "account-b", priority = Priority.VISIBLE))
        val ran = mutableListOf<String>()
        var hang = true
        val started = CompletableDeferred<Unit>()
        val runner = ScanRunner(queue, parallelism = 1) { job, _ ->
            if (hang) {
                started.complete(Unit)
                awaitCancellation()
            }
            ran += job.job.accountRef
        }
        val pass = vault.scans.launch { runner.pass(net) }
        started.await()

        vault.wipe()
        pass.join()

        assertTrue("The lock cancels the pass.", pass.isCancelled)
        assertTrue("The scan scope takes no new pass.", vault.scans.launch { runner.pass(net) }.also { it.join() }.isCancelled)
        assertTrue("No job ran after the lock.", ran.isEmpty())
        hang = false
        assertEquals("The next pass finds both jobs released.", 2, runner.pass(net))
        assertEquals(listOf("account-a", "account-b"), ran)
    }

    @Test
    fun aLockCancelsTheFirstSyncAndItsLaterStepsDoNotRun() = runBlocking {
        val session = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
        val vault = LumioVault(session, LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "sync.db")))
        val started = CompletableDeferred<Unit>()
        var later = false
        val sync = FirstSync(
            listOf(
                SyncStep(listOf(SyncRow.EVM)) {
                    started.complete(Unit)
                    awaitCancellation()
                },
                SyncStep(listOf(SyncRow.PRICES)) {
                    later = true
                    emptySet()
                },
            ),
        )
        val running = vault.startFirstSync(sync)
        started.await()

        vault.wipe()
        withTimeout(10_000) { running.join() }

        assertTrue("The lock cancels the first sync.", running.isCancelled)
        assertFalse("No step ran after the lock.", later)
    }
}
