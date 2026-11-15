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
import app.lumio.data.provider.fetchText
import okhttp3.Request as HttpRequest

/**
 * The Esplora HTTP API, the one that mempool.space and blockstream.info serve. One object serves every Esplora provider: a plugin passes the base
 * URL of its network, and this maps the typed request to its endpoint, checks the answer, and names each failure as a [ProviderError]. The
 * transport and the status rules are [fetchText] and [failureFor], shared with every provider:
 * - HTTP 429 is [ProviderError.RateLimited], with the Retry-After in milliseconds (seconds, or an HTTP date), or null when none was sent;
 * - HTTP 404 is [ProviderError.NotFound] for a block or a transaction, which the server answers that way when it does not know it;
 * - other 4xx answers, and an answer that is not in the Esplora format, are [ProviderError.Fatal];
 * - 5xx and 408 answers, and an answer over 4 MiB, are [ProviderError.Transient]. A request that gets no answer fails with an [IOException],
 *   which the scheduler retries.
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
                is TipHeight -> EsploraParse.tipHeight(get(http, "$base/blocks/tip/height", notFoundIsAnswer = false))
                is AddressStats -> EsploraParse.addressStats(get(http, "$base/address/${req.address}", notFoundIsAnswer = false))
                is AddressTxs -> EsploraParse.txs(get(http, "$base/address/${req.address}/txs", notFoundIsAnswer = false))
                is AddressUtxos -> EsploraParse.utxos(get(http, "$base/address/${req.address}/utxo", notFoundIsAnswer = false))
                    .map { Utxo(it.txid, it.vout, it.valueSats, it.blockHeight) }
                is FeeEstimates -> EsploraParse.feeEstimates(get(http, "$base/fee-estimates", notFoundIsAnswer = false))
                is Block -> EsploraParse.block(get(http, "$base/block/${req.hash}", notFoundIsAnswer = true))
                is Tx -> EsploraParse.txStatus(get(http, "$base/tx/${req.txid}", notFoundIsAnswer = true))
                else -> throw ProviderError.Unsupported()
            }
        } catch (e: EsploraException) {
            throw ProviderError.Fatal(e.message ?: "The server's answer is not in the Esplora format.")
        }
        @Suppress("UNCHECKED_CAST")
        return answer as T
    }

    /**
     * One GET through the shared transport, so the call is cancelled with the coroutine. Debug builds log one line per request: the host, the
     * outcome and the time. The path is never logged, since it names an address.
     */
    private suspend fun get(http: Http, url: String, notFoundIsAnswer: Boolean): String {
        val request = HttpRequest.Builder().url(url).header("Accept", "application/json, text/plain").get().build()
        val started = System.nanoTime()
        return try {
            fetchText(http.client, request, banMs = null, notFoundIsAnswer = notFoundIsAnswer).also { logLine(request, "ok", started) }
        } catch (e: Throwable) {
            logLine(request, "error=${e.javaClass.simpleName}", started)
            throw e
        }
    }

    /** Debug builds only: one line per request, with the host, the outcome and the time. Read it with `adb logcat -s LumioEsplora`. */
    private fun logLine(request: HttpRequest, outcome: String, started: Long) {
        if (BuildConfig.DEBUG) {
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.d(LOG_TAG, "host=${request.url.host} $outcome ms=$ms")
        }
    }
}
