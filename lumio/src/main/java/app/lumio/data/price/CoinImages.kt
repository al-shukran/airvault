package app.lumio.data.price

import app.lumio.data.cache.Cache
import app.lumio.data.cache.CachePolicy
import app.lumio.data.provider.Http
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.fetchBytes
import app.lumio.data.provider.noRedirects
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * The logos of the coins that Add to Watchlist lists, read from the image URLs the market source gives. An image is kept in memory for the session and
 * in the open vault's cache, so a list opened again draws its logos on its first frame. The images are static files on the source's image host, not
 * calls of its API, so they are read on the shared client without the API's rate bucket.
 *
 * Privacy: an image request names only the coin's logo file, as a price request names only the coin, and never an address.
 *
 * @param demo the image of a demo-mode URL, or null outside demo mode.
 */
class CoinImages(private val http: Http, private val cache: () -> Cache?, private val demo: (String) -> ByteArray? = { null }) {
    private val images = ConcurrentHashMap<String, ByteArray>()

    /** The image at [url], or null when it is not on the source's image host or does not load now. */
    suspend fun imageOf(url: String): ByteArray? {
        images[url]?.let { return it }
        demo(url)?.let { return it.also { images[url] = it } }
        val address = url.toHttpUrlOrNull()?.takeIf { it.scheme == "https" && it.host in IMAGE_HOSTS } ?: return null
        val key = "coin-image:$url"
        val store = cache()
        store?.get(CachePolicy.LastKnown, key)?.value?.let { return it.also { images[url] = it } }
        val bytes = try {
            fetchBytes(http.noRedirects(), Request.Builder().url(address).get().build(), IMAGE_LIMIT_BYTES)
        } catch (e: ProviderError) {
            return null
        } catch (e: IOException) {
            return null
        }
        images[url] = bytes
        store?.put(CachePolicy.LastKnown, key, bytes)
        return bytes
    }

    private companion object {
        /** The hosts the market source serves its logos from. */
        val IMAGE_HOSTS = setOf("coin-images.coingecko.com", "assets.coingecko.com")

        /** The largest logo read, in bytes. */
        const val IMAGE_LIMIT_BYTES = 256L * 1024
    }
}
