package app.lumio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.lensly.app.VaultHolder
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.ui.navigation.GateOutcome
import app.lumio.data.db.LumioDatabase
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.network.NetworkMode
import app.lumio.watchlist.WatchedAsset
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The coin activity read ahead of the push to the Asset page belongs to the open vault: a lock drops it, and a read that the lock cuts short is not an error. */
@RunWith(RobolectricTestRunner::class)
@Config(application = LumioApp::class)
class CoinActivityPreloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: LumioApp get() = context.applicationContext as LumioApp
    private val vault by lazy { Vault(VaultLayout(File(tmp.root, "lumio-vault")), MemoryKeystore(), FakeClock(), KDF) }
    private val database by lazy { LumioDatabase.open(context, File(tmp.root, "lumio.db")) }
    private val holder by lazy { VaultHolder(vault, attach = { LumioVault(it, database) }) }
    private val key = CoinActivityKey(WatchedAsset(NetworkId(Networks.BTC_MAINNET, testnet = false)), NetworkMode.MAINNET, includeSpam = false)

    private fun open(): LumioVault = runBlocking {
        assertEquals(GateOutcome.Opened, createFirstVault(holder, PASSWORD.toCharArray(), context))
        checkNotNull(holder.sessionAttachment() as? LumioVault)
    }

    @Test
    fun aReadKeepsItsPageUntilTheVaultLocks(): Unit = runBlocking {
        val open = open()

        assertNotNull(CoinActivityPreload.read(app, open, key))
        assertNotNull(CoinActivityPreload.pageOf(open, key))

        holder.lock()

        assertNull(CoinActivityPreload.pageOf(open, key))
    }

    @Test
    fun aReadOfAVaultThatLockedGivesNothingAndDoesNotThrow(): Unit = runBlocking {
        val open = open()
        holder.lock()

        assertNull(CoinActivityPreload.read(app, open, key))
        CoinActivityPreload.preload(app, open, key)
    }

    @Test
    fun aTapThatStopsWaitingLeavesTheReadToKeepItsPage(): Unit = runBlocking {
        val open = open()

        CoinActivityPreload.preload(app, open, key)

        val deadline = System.currentTimeMillis() + 10_000
        while (CoinActivityPreload.pageOf(open, key) == null) {
            check(System.currentTimeMillis() < deadline) { "the read did not keep its page" }
            Thread.sleep(10)
        }
    }

    /** A read that outlasts the wait does not hold the tap: preload returns after [CoinActivityPreload.WAIT_MS] of virtual time, and the read goes on to keep its page. */
    @Test
    fun aSlowReadDoesNotHoldTheTapBeyondTheWaitAndKeepsItsPageWhenItFinishes() = runTest {
        val open = open()
        val gate = CompletableDeferred<Unit>()

        val started = testScheduler.currentTime
        withTimeout(CoinActivityPreload.WAIT_MS + SLACK_MS) {
            CoinActivityPreload.preload(app, open, key) {
                gate.await()
                CoinActivityPreload.read(app, open, key, live = false)
            }
        }

        assertEquals(CoinActivityPreload.WAIT_MS, testScheduler.currentTime - started)
        assertNull(CoinActivityPreload.pageOf(open, key))
        gate.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (CoinActivityPreload.pageOf(open, key) == null) delay(10) }
        }
    }

    @Test
    fun aLockCancelsAReadThatOutlastedTheWait(): Unit = runBlocking {
        val open = open()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        CoinActivityPreload.preload(app, open, key) {
            try {
                started.complete(Unit)
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled.complete(Unit)
                throw e
            }
        }
        started.await()
        holder.lock()

        withTimeout(10_000) { cancelled.await() }
    }

    private companion object {
        const val SLACK_MS = 50L
        const val PASSWORD = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
