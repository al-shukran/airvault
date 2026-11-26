package app.lumio.data.cache

import app.lumio.data.Clock
import app.lumio.data.db.LumioDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The cache over the real SQLite database. A row is read back through a cursor window of about 2 MB, so the cache refuses a value over
 * [MAX_VALUE_BYTES], and the largest value it takes is written, read back, and survives a pass over every row.
 */
@RunWith(RobolectricTestRunner::class)
class CacheSizeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var database: LumioDatabase
    private lateinit var cache: Cache

    @Before
    fun open() {
        database = LumioDatabase.open(RuntimeEnvironment.getApplication(), File(tmp.root, "cache.db"))
        cache = Cache(database.kv(), CacheKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }), object : Clock {
            override fun nowMs(): Long = 1_000L
        })
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun theLargestValueTheCacheTakesIsReadBackWhole() = runBlocking {
        val largest = ByteArray(MAX_VALUE_BYTES) { (it * 31).toByte() }

        cache.put(CachePolicy.LastKnown, "largest", largest)
        cache.purgeUnreadable()

        assertArrayEquals(largest, cache.get(CachePolicy.LastKnown, "largest")!!.value)
    }

    @Test
    fun aValueOverTheCapIsRefusedAndNothingIsStored() = runBlocking {
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { cache.put(CachePolicy.LastKnown, "too-large", ByteArray(MAX_VALUE_BYTES + 1)) }
        }

        assertEquals("A cached value is at most $MAX_VALUE_BYTES bytes; too-large has ${MAX_VALUE_BYTES + 1}.", error.message)
        assertNull(cache.get(CachePolicy.LastKnown, "too-large"))
    }

    @Test
    fun aDeletedValueIsGone() = runBlocking {
        cache.put(CachePolicy.LastKnown, "kept", byteArrayOf(1))

        cache.delete(CachePolicy.LastKnown, "kept")

        assertNull(cache.get(CachePolicy.LastKnown, "kept"))
    }
}
