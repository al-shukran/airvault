package app.lumio.btc

import app.lumio.data.config.ProviderConfig
import app.lumio.data.provider.Http
import app.lumio.data.provider.fetchText
import app.lumio.data.provider.noRedirects
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** The provider-settings id that keeps the owner's Ordinals indexer. The router never reads it, because it is not a provider. */
const val ORD_INDEXER_ID = "ord-indexer"

/** How long the lookups of one send may take. An output not answered by then stays unmarked, so a send never waits on the indexer longer. */
const val INDEXER_BUDGET_MS = 5_000L

/** A transaction id: 64 hex digits. An output whose id is not one is never asked about. */
private val TXID = Regex("[0-9a-fA-F]{64}")

/**
 * The owner's Ordinals indexer: an `ord` server that the owner set in Lumio's Bitcoin settings. Lumio asks it about each confirmed output of the
 * account, not only the outputs a send spends, because the marks steer which coins a send picks. Each is asked with
 * `GET <base>/output/<txid>:<vout>`, and an output whose answer names an inscription or a rune is marked (spec 5.5). Nothing is asked without a
 * server, so the owner's outputs reach no server by default. A lookup that fails, times out or does not parse leaves its output unmarked, and
 * Lensly then shows its caution.
 */
class OrdIndexer(http: Http, private val base: HttpUrl, private val budgetMs: Long = INDEXER_BUDGET_MS) {
    // No redirect is followed: an answer that moves the lookup to another host would send the owner's outputs there.
    private val client: OkHttpClient = http.noRedirects()

    /**
     * The outputs among [outputs], each a transaction id and an output index, that the indexer marks. The lookups run in parallel, and the batch
     * stops after [budgetMs]: an output not answered by then is unmarked. A failed lookup never throws.
     */
    suspend fun markedOutputs(outputs: List<Pair<String, Int>>): Set<Pair<String, Int>> {
        val marked = ConcurrentHashMap.newKeySet<Pair<String, Int>>()
        withTimeoutOrNull(budgetMs) {
            for (output in outputs) {
                if (TXID.matches(output.first)) launch { if (isMarked(output)) marked += output }
            }
        }
        return marked
    }

    private suspend fun isMarked(output: Pair<String, Int>): Boolean = try {
        markedAnswer(fetchText(client, request(output), banMs = null))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private fun request(output: Pair<String, Int>): Request = Request.Builder()
        .url(base.newBuilder().addPathSegment("output").addPathSegment("${output.first}:${output.second}").build())
        .header("Accept", "application/json")
        .build()
}

/**
 * Whether an `ord` answer names an inscription or a rune on its output: a non-empty `inscriptions` array, or a non-empty `runes` map or array. Each
 * field is judged on its own, so a field of another shape contributes nothing. Text that is not a JSON object marks nothing.
 */
internal fun markedAnswer(text: String): Boolean {
    val root = try {
        Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        return false
    } catch (e: IllegalArgumentException) {
        return false
    }
    val answer = root as? JsonObject ?: return false
    return (answer["inscriptions"] as? JsonArray)?.isNotEmpty() == true ||
        (answer["runes"] as? JsonObject)?.isNotEmpty() == true ||
        (answer["runes"] as? JsonArray)?.isNotEmpty() == true
}

/**
 * The indexer that [configs] keep, on [http], or null when the owner set none or its address is not https: a send then asks no server about its
 * outputs.
 */
fun indexerOf(configs: Map<String, ProviderConfig>, http: Http): OrdIndexer? =
    indexerUrlOf(configs)?.toHttpUrlOrNull()?.takeIf { it.scheme == "https" }?.let { OrdIndexer(http, it) }

/** The indexer's base URL that [configs] keep, or null when the owner set none. */
fun indexerUrlOf(configs: Map<String, ProviderConfig>): String? = configs[ORD_INDEXER_ID]?.baseUrl

/** [configs] with the indexer set to [url], or with no indexer when [url] is null. Every other setting is kept. */
fun withIndexerUrl(configs: Map<String, ProviderConfig>, url: String?): Map<String, ProviderConfig> =
    if (url == null) configs - ORD_INDEXER_ID else configs + (ORD_INDEXER_ID to ProviderConfig(baseUrl = url))
