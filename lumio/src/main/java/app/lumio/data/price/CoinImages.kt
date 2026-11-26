package app.lumio.data.price

import android.graphics.Bitmap
import app.lumio.data.cache.Cache
import app.lumio.data.cache.CachePolicy
import app.lumio.data.provider.Http
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.fetchBytes
import app.lumio.data.provider.noRedirects
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * The logos of the coins that Add to Watchlist lists and the watchlist keeps, read from the image URLs the market source gives. An image is kept in
 * memory for the open vault, at most [MEMORY_IMAGES] of them, and in its cache, so a list opened again draws its logos on its first frame. The memory
 * goes when the vault locks or another one opens. The images are static files on the source's image host, not calls of its API, so they are read on
 * the shared client without the API's rate bucket.
 *
 * Privacy: an image request names only the coin's logo file, as a price request names only the coin, and never an address.
 *
 * @param decoder how an image is decoded, which a test replaces.
 * @param demo the image of a demo-mode URL, or null outside demo mode.
 */
class CoinImages(
    private val http: Http,
    private val cache: () -> Cache?,
    private val decoder: (ByteArray) -> Bitmap? = ::decodeLogoOrThrow,
    private val demo: (String) -> ByteArray? = { null },
) {
    private var owner: Cache? = null
    private val images = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > MEMORY_IMAGES
    }

    /** The image at [url], or null when the vault is locked, the URL is not on the source's image host, or the image does not load now. */
    suspend fun imageOf(url: String): ByteArray? = load(url)?.also { it.bitmap?.recycle() }?.bytes

    /**
     * The logo at [url], decoded, or null when [imageOf] has none or the logo does not fit in memory now. A logo read for the first time is decoded once,
     * to check it and to draw it. The caller owns the bitmap.
     */
    suspend fun logoOf(url: String): Bitmap? = load(url)?.let { it.bitmap ?: decode(it.bytes) }

    /** An image's [bytes], and its [bitmap] when it was decoded to check it. */
    private class Loaded(val bytes: ByteArray, val bitmap: Bitmap? = null)

    /** The decoded bitmap of [bytes], or null when they do not decode or there is no memory for them now. */
    private fun decode(bytes: ByteArray): Bitmap? = try {
        decoder(bytes)
    } catch (e: OutOfMemoryError) {
        null
    }

    private suspend fun load(url: String): Loaded? {
        val store = cache() ?: return null.also { remember(null, null, null) }
        remember(store, url, null)?.let { return Loaded(it) }
        demo(url)?.let { bytes -> remember(store, url, bytes); return Loaded(bytes) }
        val address = url.toHttpUrlOrNull()?.takeIf { it.scheme == "https" && it.host in IMAGE_HOSTS } ?: return null
        val key = "coin-image:$url"
        val kept = try {
            store.get(CachePolicy.LastKnown, key)?.value
        } catch (e: Exception) {
            // A kept image that cannot be read is dropped and read again.
            runCatching { store.delete(CachePolicy.LastKnown, key) }
            null
        }
        if (kept != null) {
            val bitmap = try {
                decoder(kept)
            } catch (e: OutOfMemoryError) {
                // No memory to check it now: the kept image is not known to be bad, so it is shown as it is and checked when drawn.
                return Loaded(kept).also { remember(store, url, kept) }
            }
            if (bitmap != null) return Loaded(kept, bitmap).also { remember(store, url, kept) }
            // A kept image that does not decode is dropped and fetched again.
            runCatching { store.delete(CachePolicy.LastKnown, key) }.onFailure { if (it is CancellationException) throw it }
        }
        val bytes = try {
            fetchBytes(http.noRedirects(), Request.Builder().url(address).get().build(), IMAGE_LIMIT_BYTES)
        } catch (e: ProviderError) {
            return null
        } catch (e: IOException) {
            return null
        }
        // An image that does not decode within the size limit is neither kept nor shown.
        val bitmap = decode(bytes) ?: return null
        remember(store, url, bytes)
        // The cache is best effort: a write that fails still shows the image, and it is read again next time.
        runCatching { store.put(CachePolicy.LastKnown, key, bytes) }.onFailure { if (it is CancellationException) throw it }
        return Loaded(bytes, bitmap)
    }

    /** The image kept in memory for [url] under the vault of [store], keeping [bytes] when given. A different vault, or none, empties the memory. */
    @Synchronized
    private fun remember(store: Cache?, url: String?, bytes: ByteArray?): ByteArray? {
        if (store !== owner) {
            images.clear()
            owner = store
        }
        if (url == null) return null
        if (bytes != null) images[url] = bytes
        return images[url]
    }

    private companion object {
        /** The hosts the market source serves its logos from. */
        val IMAGE_HOSTS = setOf("coin-images.coingecko.com", "assets.coingecko.com")

        /** The largest logo read, in bytes. */
        const val IMAGE_LIMIT_BYTES = 256L * 1024

        /** The most images kept in memory. */
        const val MEMORY_IMAGES = 256
    }
}
