package app.lumio.btc

import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/** One side of an address's history as Esplora writes it: the funded and spent outputs, and the transactions. */
internal fun side(
    fundedCount: Long = 0,
    fundedSum: Long = 0,
    spentCount: Long = 0,
    spentSum: Long = 0,
    txCount: Long = 0,
): String = """{"funded_txo_count":$fundedCount,"funded_txo_sum":$fundedSum,"spent_txo_count":$spentCount,""" +
    """"spent_txo_sum":$spentSum,"tx_count":$txCount}"""

/** The answer of `GET /address/{address}`. */
internal fun addressJson(chain: String = side(), mempool: String = side()): String =
    """{"address":"fixture","chain_stats":$chain,"mempool_stats":$mempool}"""

/**
 * A local Esplora for the tests, served by a MockWebServer under /api. Addresses that are not set answer with an empty history, so a scan
 * sees them as unused. Every request path is recorded in order.
 */
internal class FakeEsplora : Dispatcher() {
    /** Each address's `GET /address/{address}` answer. */
    val stats = mutableMapOf<String, String>()

    /** Each address's `GET /address/{address}/txs` answer. */
    val txs = mutableMapOf<String, String>()

    /** Each address's `GET /address/{address}/utxo` answer. */
    val utxos = mutableMapOf<String, String>()

    /** The answer of `GET /blocks/tip/height`. */
    var tip: String = "850000\n"

    /** The HTTP status of every answer. Anything but 200 is sent without a body. */
    var status: Int = 200

    /** Every request path, in the order it was asked for. */
    val paths = CopyOnWriteArrayList<String>()

    /** The addresses whose history was asked for, in order. The transaction and UTXO paths are not counted. */
    fun statsRequests(): List<String> = paths
        .filter { it.startsWith("/api/address/") && it.count { c -> c == '/' } == 3 }
        .map { it.substringAfterLast('/') }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        paths += path
        if (status != 200) return MockResponse().setResponseCode(status)
        val parts = path.split("/")
        val body = when {
            path == "/api/blocks/tip/height" -> tip
            path == "/api/fee-estimates" -> FEES
            parts.size == 4 && parts[1] == "api" && parts[2] == "address" -> stats[parts[3]] ?: addressJson()
            parts.size == 5 && parts[2] == "address" && parts[4] == "txs" -> txs[parts[3]] ?: "[]"
            parts.size == 5 && parts[2] == "address" && parts[4] == "utxo" -> utxos[parts[3]] ?: "[]"
            else -> return MockResponse().setResponseCode(404)
        }
        return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }

    private companion object {
        const val FEES = """{"1":87.5,"6":12.25,"144":1.0}"""
    }
}
