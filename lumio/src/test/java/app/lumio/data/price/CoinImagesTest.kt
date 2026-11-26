package app.lumio.data.price

import app.lumio.data.FakeKvDao
import app.lumio.data.cache.Cache
import app.lumio.data.cache.CacheKeys
import app.lumio.data.provider.Http
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The logo images keep to the open vault: none is read while it is locked, and another vault starts with an empty memory. */
class CoinImagesTest {
    private fun vault() = Cache(FakeKvDao(), CacheKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }), { 0L })

    @Test
    fun theMemoryOfOneVaultIsNotTheNextOnes() = runBlocking {
        var open: Cache? = vault()
        var reads = 0
        val images = CoinImages(Http(OkHttpClient()), { open }) { url -> if (url == URL) byteArrayOf((++reads).toByte()) else null }

        assertArrayEquals(byteArrayOf(1), images.imageOf(URL))
        assertArrayEquals(byteArrayOf(1), images.imageOf(URL))
        assertEquals(1, reads)

        open = null
        assertNull(images.imageOf(URL))

        open = vault()
        assertArrayEquals(byteArrayOf(2), images.imageOf(URL))
        assertEquals(2, reads)
    }

    @Test
    fun anImageOffTheSourcesHostIsNeverRead() = runBlocking {
        assertNull(CoinImages(Http(OkHttpClient()), { vault() }).imageOf("https://example.com/logo.png"))
    }

    private companion object {
        const val URL = "demo:bitcoin"
    }
}
