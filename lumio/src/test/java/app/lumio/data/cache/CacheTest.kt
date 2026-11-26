package app.lumio.data.cache

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.data.ETH
import app.lumio.data.FakeClock
import app.lumio.data.FakeProvider
import app.lumio.data.MonotonicClock
import app.lumio.data.containsBytes
import app.lumio.data.db.LumioDatabase
import app.lumio.data.provider.Http
import app.lumio.data.provider.TipHeight
import app.lumio.data.sched.Scheduler
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The cache's three policies, and what its database holds, on Robolectric with a real Room database file. */
@RunWith(RobolectricTestRunner::class)
class CacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun session(seed: Int): VaultSession =
        VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 5 + seed).toByte() })

    private fun keys(seed: Int = 1): CacheKeys = CacheKeys.from(session(seed))

    @Test
    fun shortTtlValuesExpireAfterTheirTimeToLive() = runBlocking {
        val clock = FakeClock(1_000L)
        val database = LumioDatabase.open(context, File(tmp.root, "ttl.db"))
        val cache = Cache(database.kv(), keys(), clock)
        val policy = CachePolicy.ShortTtl(ttlMs = 60_000L)

        cache.put(policy, "balance:eth:1", byteArrayOf(7))
        clock.now += 59_999L
        assertNotNull(cache.get(policy, "balance:eth:1"))

        clock.now += 1L
        assertNull(cache.get(policy, "balance:eth:1"))
        database.close()
    }

    @Test
    fun immutableValuesSurviveAReopenOfTheDatabase() = runBlocking {
        val database = LumioDatabase.open(context, File(tmp.root, "reopen.db"))
        Cache(database.kv(), keys(), FakeClock(1L)).put(CachePolicy.Immutable, "block:42", "header".toByteArray())
        database.close()

        val reopened = LumioDatabase.open(context, File(tmp.root, "reopen.db"))
        val entry = Cache(reopened.kv(), keys(), FakeClock(2L)).get(CachePolicy.Immutable, "block:42")
        reopened.close()

        assertEquals("header", entry?.value?.decodeToString())
    }

    @Test
    fun valuesAreUnreadableWithoutTheVaultKeyAndAreNeverStoredInTheClear() = runBlocking {
        val marker = "plain-marker-8731"
        val database = LumioDatabase.open(context, File(tmp.root, "sealed.db"))
        Cache(database.kv(), keys(seed = 1), FakeClock(1L)).put(CachePolicy.Immutable, "tx:cafe", marker.toByteArray())
        database.close()

        val file = File(tmp.root, "sealed.db").readBytes()
        assertTrue(file.isNotEmpty())
        assertFalse(file.containsBytes(marker.toByteArray()))
        assertFalse(file.containsBytes("tx:cafe".toByteArray()))

        val reopened = LumioDatabase.open(context, File(tmp.root, "sealed.db"))
        assertNull(Cache(reopened.kv(), keys(seed = 2), FakeClock(2L)).get(CachePolicy.Immutable, "tx:cafe"))
        reopened.close()
    }

    @Test
    fun theLeastRecentlyUsedImmutableValueGoesFirstWhenTheCapIsReached() = runBlocking {
        val clock = FakeClock(0L)
        val database = LumioDatabase.open(context, File(tmp.root, "lru.db"))
        val cache = Cache(database.kv(), keys(), clock, immutableCapBytes = 300L)
        val value = ByteArray(100) { 1 }

        cache.put(CachePolicy.Immutable, "a", value)
        clock.now = 1L
        cache.put(CachePolicy.Immutable, "b", value)
        clock.now = 2L
        cache.put(CachePolicy.Immutable, "c", value)

        assertNull(cache.get(CachePolicy.Immutable, "a"))
        assertNotNull(cache.get(CachePolicy.Immutable, "b"))
        assertNotNull(cache.get(CachePolicy.Immutable, "c"))
        database.close()
    }

    @Test
    fun aDecoyThatFillsTheCapNeverEvictsTheRealVaultsRows() = runBlocking {
        val clock = FakeClock(0L)
        val database = LumioDatabase.open(context, File(tmp.root, "shared.db"))
        val real = Cache(database.kv(), keys(seed = 1), clock, immutableCapBytes = 300L)
        val decoy = Cache(database.kv(), keys(seed = 2), clock, immutableCapBytes = 300L)
        val value = ByteArray(100) { 1 }
        real.put(CachePolicy.Immutable, "a", value)
        clock.now = 1L
        real.put(CachePolicy.Immutable, "b", value)

        clock.now = 2L
        decoy.put(CachePolicy.Immutable, "x", value)
        clock.now = 3L
        decoy.put(CachePolicy.Immutable, "y", value)
        // The cap is full of rows the decoy cannot read, so its own rows are what it gives up.

        assertNotNull(real.get(CachePolicy.Immutable, "a"))
        assertNotNull(real.get(CachePolicy.Immutable, "b"))
        database.close()
    }

    @Test
    fun lastKnownValuesStayUntilReplacedAndKeepTheirTime() = runBlocking {
        val clock = FakeClock(5_000L)
        val database = LumioDatabase.open(context, File(tmp.root, "last.db"))
        val cache = Cache(database.kv(), keys(), clock)
        val name = "wallet-1|eth|0xabc"

        cache.put(CachePolicy.LastKnown, name, "1 ETH".toByteArray())
        clock.now = 900_000L
        assertEquals(5_000L, cache.get(CachePolicy.LastKnown, name)?.atMs)

        cache.put(CachePolicy.LastKnown, name, "2 ETH".toByteArray())
        val replaced = cache.get(CachePolicy.LastKnown, name)
        assertEquals("2 ETH", replaced?.value?.decodeToString())
        assertEquals(900_000L, replaced?.atMs)
        database.close()
    }

    @Test
    fun aCachedResultIsServedBeforeAnyProviderIsRouted() = runBlocking {
        val database = LumioDatabase.open(context, File(tmp.root, "routing.db"))
        val provider = FakeProvider("a")
        val cache = Cache(database.kv(), keys(), FakeClock(1L))
        val scheduler = Scheduler(
            providers = listOf(provider),
            configs = { emptyMap() },
            http = Http(OkHttpClient()),
            clock = MonotonicClock,
            random = Random(3),
            scope = this,
            cache = { cache },
        )
        val use = CacheUse(CachePolicy.ShortTtl(), "tip", LongCodec)

        assertEquals(100L, scheduler.run(TipHeight(ETH), cacheUse = use))
        assertEquals(100L, scheduler.run(TipHeight(ETH), cacheUse = use))
        assertEquals(1, provider.calls)
        database.close()
    }

    private object LongCodec : Codec<Long> {
        override fun encode(value: Long): ByteArray = value.toString().toByteArray()

        override fun decode(bytes: ByteArray): Long = bytes.decodeToString().toLong()
    }
}
