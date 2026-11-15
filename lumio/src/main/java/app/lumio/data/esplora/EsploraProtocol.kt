package app.lumio.data.esplora

import android.util.Log
import app.lumio.BuildConfig
import app.lumio.btc.EsploraException
import app.lumio.btc.EsploraParse
import app.lumio.data.provider.AddressStats
import app.lumio.data.provider.AddressTxs
import app.lumio.data.provider.AddressUtxos
import app.lumio.data.provider.Block
import app.lumio.data.provider.FeeEstimates
import app.lumio.data.provider.Http
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.TipHeight
import app.lumio.data.provider.Tx
import app.lumio.data.provider.Utxo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.Request as HttpRequest

/**
 * The Esplora HTTP API, the one that mempool.space and blockstream.info serve. One object serves every Esplora provider: a plugin passes the base
 * URL of its network, and this maps the typed request to its endpoint, checks the answer, and names each failure as a [ProviderError]:
 * - HTTP 429 is [ProviderError.RateLimited], with the Retry-After in milliseconds (seconds, or an HTTP date), or null when none was sent;
 * - HTTP 404 is [ProviderError.NotFound] for a block or a transaction, which the server answers that way when it does not know it;
 * - other 4xx answers, and an answer that is not in the Esplora format or is over 4 MiB, are [ProviderError.Fatal];
 * - 5xx answers are [ProviderError.Transient]. A request that gets no answer fails with an [IOException], which the scheduler retries.
 *
 * The endpoints are those of the Esplora API document, https://github.com/Blockstream/esplora/blob/master/API.md. mempool.space serves the same
 * paths, and its rate limits are described at https://mempool.space/docs/api/rest.
 */
object EsploraProtocol {
    /** The tag of the debug-build request log. */
    const val LOG_TAG = "LumioEsplora"

    /** Serves [req] from the Esplora server at [baseUrl], such as https://mempool.space/api. */
    suspend fun <T> execute(req: Request<T>, baseUrl: String, http: Http): T {
        val base = baseUrl.trimEnd('/')
        val answer: Any = try {
            when (req) {
                is TipHeight -> EsploraParse.tipHeight(get(http, "$base/blocks/tip/height", missingIsAnswer = false))
                is AddressStats -> EsploraParse.addressStats(get(http, "$base/address/${req.address}", missingIsAnswer = false))
                is AddressTxs -> EsploraParse.txs(get(http, "$base/address/${req.address}/txs", missingIsAnswer = false))
                is AddressUtxos -> EsploraParse.utxos(get(http, "$base/address/${req.address}/utxo", missingIsAnswer = false))
                    .map { Utxo(it.txid, it.vout, it.valueSats, it.blockHeight) }
                is FeeEstimates -> EsploraParse.feeEstimates(get(http, "$base/fee-estimates", missingIsAnswer = false))
                is Block -> EsploraParse.block(get(http, "$base/block/${req.hash}", missingIsAnswer = true))
                is Tx -> EsploraParse.txStatus(get(http, "$base/tx/${req.txid}", missingIsAnswer = true))
                else -> throw ProviderError.Unsupported()
            }
        } catch (e: EsploraException) {
            throw ProviderError.Fatal(e.message ?: "The server's answer is not in the Esplora format.")
        }
        @Suppress("UNCHECKED_CAST")
        return answer as T
    }

    /** One GET. The call is cancelled with the coroutine, so a request that the scheduler gives up on stops at once. */
    private suspend fun get(http: Http, url: String, missingIsAnswer: Boolean): String {
        val call = http.client.newCall(HttpRequest.Builder().url(url).header("Accept", "application/json, text/plain").get().build())
        val started = System.nanoTime()
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    logLine(call, "error=${e.javaClass.simpleName}", started)
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    logLine(call, "status=${response.code}", started)
                    val result = runCatching { response.use { answer(it, missingIsAnswer) } }
                    if (cont.isActive) cont.resumeWith(result)
                }
            })
        }
    }

    /**
     * Debug builds only: one line per request, with the host, the status and the time. The path is never logged, since it names an address. Read it
     * with `adb logcat -s LumioEsplora`.
     */
    private fun logLine(call: Call, outcome: String, started: Long) {
        if (BuildConfig.DEBUG) {
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.d(LOG_TAG, "host=${call.request().url.host} $outcome ms=$ms")
        }
    }

    /** The body of a 2xx answer, or the [ProviderError] that the status names. */
    private fun answer(response: Response, missingIsAnswer: Boolean): String {
        val code = response.code
        return when {
            code in 200..299 -> {
                val body = response.body ?: throw ProviderError.Fatal("The server sent no answer.")
                readBounded(body.byteStream()).toString(Charsets.UTF_8)
            }
            code == 429 -> throw ProviderError.RateLimited(retryAfterMs(response.header("Retry-After"), System.currentTimeMillis()))
            code == 404 && missingIsAnswer -> throw ProviderError.NotFound()
            code in 400..499 -> throw ProviderError.Fatal("The server refused the request (HTTP $code).")
            else -> throw ProviderError.Transient()
        }
    }

    /**
     * The wait that a Retry-After header asks for, in milliseconds. It is a count of seconds, or an HTTP date that is read against [nowMs]. Null when
     * the header is missing or cannot be read, so the scheduler uses its own backoff.
     */
    internal fun retryAfterMs(header: String?, nowMs: Long): Long? {
        val text = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toLongOrNull()?.let { seconds ->
            return if (seconds < 0) null else seconds.coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1_000L
        }
        return try {
            val at = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (at - nowMs).coerceAtLeast(0L)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** Reads [input] in full, and refuses an answer over [MAX_BODY_BYTES], so a server cannot fill the memory. */
    private fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (out.size() + read > MAX_BODY_BYTES) throw EsploraException("The server's answer is larger than Lumio reads.")
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    /** The largest answer Lumio reads. The longest history page is far below it. */
    private const val MAX_BODY_BYTES = 4 shl 20

    /** A Retry-After longer than a day is cut to a day, so a bad header cannot keep a provider out for longer. */
    private const val MAX_RETRY_AFTER_SECONDS = 86_400L
}
