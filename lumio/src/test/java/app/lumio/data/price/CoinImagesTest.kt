package app.lumio.data.price

import app.lumio.data.FakeKvDao
import app.lumio.data.cache.Cache
import app.lumio.data.cache.CacheKeys
import app.lumio.data.cache.CachePolicy
import app.lumio.data.db.KvDao
import app.lumio.data.db.KvRow
import app.lumio.data.provider.Http
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.util.zip.CRC32
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The logo images keep to the open vault: none is read while it is locked, and another vault starts with an empty memory. An image that is no
 * image, or declares too many pixels, is neither kept nor shown, and a cache that cannot be written to does not cost the image.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    @Test
    fun anImageThatIsNoImageOrDeclaresTooManyPixelsIsNotKeptAndDoesNotCrash() = runBlocking {
        val dao = FakeKvDao()
        val vault = Cache(dao, CacheKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }), { 0L })
        assertTrue(isLogo(pngDeclaring(64, 64)))
        for (bytes in listOf("not an image".toByteArray(), pngDeclaring(30_000, 30_000))) {
            val images = CoinImages(Http(serving(bytes)), { vault })
            assertNull(images.imageOf(LOGO))
            assertNull(vault.get(CachePolicy.LastKnown, "coin-image:$LOGO"))
            assertNull(decodeLogo(bytes))
        }
        assertEquals(emptyList<KvRow>(), dao.all())
    }

    @Test
    fun aLogoWithinTheLimitIsKeptAndDecoded() = runBlocking {
        val vault = vault()
        val png = pngDeclaring(64, 64)
        assertArrayEquals(png, CoinImages(Http(serving(png)), { vault }).imageOf(LOGO))
        assertArrayEquals(png, vault.get(CachePolicy.LastKnown, "coin-image:$LOGO")?.value)
    }

    @Test
    fun aCacheThatCannotBeWrittenStillShowsTheImage() = runBlocking {
        val failing = object : KvDao by FakeKvDao() {
            override suspend fun put(row: KvRow) = throw IllegalStateException("database is full")
        }
        val png = pngDeclaring(64, 64)
        val images = CoinImages(Http(serving(png)), { Cache(failing, CacheKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }), { 0L }) })
        assertArrayEquals(png, images.imageOf(LOGO))
    }

    /** A client that answers every request with [body]. */
    private fun serving(body: ByteArray) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("image/png".toMediaType())).build()
    }.build()

    /** A PNG that declares [width] x [height] pixels over a few bytes of pixel data: the bounds read sees the size, a full decode would fail. */
    private fun pngDeclaring(width: Int, height: Int): ByteArray {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val body = type.toByteArray() + data
            val crc = CRC32().apply { update(body) }.value.toInt()
            return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(body).putInt(crc).array()
        }
        val header = ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + chunk("IHDR", header) + chunk("IDAT", byteArrayOf(0x78, 0x01, 0x03, 0, 0, 0, 0, 1)) + chunk("IEND", ByteArray(0))
    }

    private companion object {
        const val URL = "demo:bitcoin"
        const val LOGO = "https://coin-images.coingecko.com/coins/images/1/large/bitcoin.png"
    }
}
