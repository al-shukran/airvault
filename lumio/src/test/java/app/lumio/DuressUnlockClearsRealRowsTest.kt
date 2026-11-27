package app.lumio

import app.lensly.app.VaultHolder
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.btc.BtcSnapshot
import app.lumio.data.cache.CachePolicy
import app.lumio.data.db.LumioDatabase
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.scan.ScanJob
import app.lumio.data.scan.ScanQueue
import app.lumio.data.sched.Priority
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The database holds the real vault's rows, which the promoted vault's key cannot read. Unlocking with the Duress Password erases the
 * real vault, so opening the promoted session through [LumioApp] runs the purge of unreadable rows, and the real wallets' cache, scans and
 * queued jobs go with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LumioApp::class)
class DuressUnlockClearsRealRowsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val app: LumioApp get() = RuntimeEnvironment.getApplication() as LumioApp

    @Test
    fun theDuressUnlockClearsTheRealCacheScansAndJobs() = runBlocking {
        val clock = FakeClock()
        val vault = Vault(VaultLayout(File(tmp.root, "lumio-vault")), MemoryKeystore(), clock, KdfParams(memoryKiB = 8 * 1024, iterations = 1))
        assertEquals(CreateResult.Created, vault.create(REAL.toCharArray()))
        val real = unlocked(vault.unlock(REAL.toCharArray()))
        val holder = VaultHolder(vault)
        holder.adopt(real, holder.unlockEpoch())
        assertEquals(PasswordChange.Changed, setLumioDuressPassword(holder, REAL, DURESS, DURESS))
        holder.lock()
        clock.advance(ONE_DAY_MS)
        val realSession = unlocked(vault.unlock(REAL.toCharArray()))
        clock.advance(ONE_DAY_MS)

        // The real rows go in through a vault of their own, so no purge of the app's runs against them while they are seeded.
        val database = LumioDatabase.open(app, lumioDatabaseFile(VaultLayout.of(app)))
        LumioVault(realSession, database).let { seeded ->
            seeded.cache.put(CachePolicy.LastKnown, "native-price:ETH", "3000".toByteArray())
            seeded.store.save(SNAPSHOT)
            ScanQueue { seeded.scanRows }.enqueue(JOB)
        }
        val reopenedBefore = LumioVault(realSession, database)
        assertNotNull(reopenedBefore.cache.get(CachePolicy.LastKnown, "native-price:ETH"), "The rows are readable before the unlock.")

        clock.advance(ONE_DAY_MS)
        val promoted = unlocked(vault.unlock(DURESS.toCharArray()))
        assertEquals(1, promoted.side)
        val appHolder = app.vaultHolder
        appHolder.adopt(promoted, appHolder.unlockEpoch())
        // The purge runs on the app's own scope, so it shows only after the scope has had time to run it.
        delay(SETTLE_MS)

        val reopened = LumioVault(realSession, database)
        assertNull(reopened.cache.get(CachePolicy.LastKnown, "native-price:ETH"), "The real cache entry goes.")
        assertNull(reopened.store.snapshot(SNAPSHOT.key), "The real scan goes.")
        assertEquals(emptyList(), ScanQueue { reopened.scanRows }.pending().map { it.job }, "The real queued job goes.")
        appHolder.lock()
        realSession.close()
        database.close()
    }

    private fun unlocked(result: UnlockResult): VaultSession {
        assertIs<UnlockResult.Unlocked>(result)
        return result.session
    }

    private companion object {
        const val REAL = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Hj3\$kP9wQz!Lm6Tn2Ysd"
        const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
        const val SETTLE_MS = 1_500L
        val JOB = ScanJob("wallet-1", NetworkId(Networks.BTC_TESTNET4, testnet = true), SNAPSHOT_KEY, Priority.VISIBLE)
        const val SNAPSHOT_KEY = "wallet-1_TESTNET4_NATIVE_SEGWIT"
        val SNAPSHOT = BtcSnapshot(
            key = SNAPSHOT_KEY,
            identity = "tb1qidentity",
            scannedAtMs = 1L,
            tipHeight = 1L,
            confirmedSats = 5L,
            unconfirmedSats = 0L,
            nextReceiveIndex = 0,
            utxos = emptyList(),
            transactions = emptyList(),
        )
    }
}
