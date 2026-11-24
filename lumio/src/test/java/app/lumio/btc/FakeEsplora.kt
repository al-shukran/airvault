package app.lumio.btc

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

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

    /** The addresses whose every request is answered 500, so a scan that reaches one fails there. */
    val failing = mutableSetOf<String>()

    /** The addresses whose `GET /address/{address}/utxo` is answered 500, while their history is still answered. */
    val failingUtxos = mutableSetOf<String>()

    /** Every request path, in the order it was asked for. */
    val paths = CopyOnWriteArrayList<String>()

    /** Answers sent ahead of the rest, one per request, so a test can script a busy answer followed by a good one. */
    val queued = ConcurrentLinkedQueue<MockResponse>()

    /** The addresses whose history was asked for, in order. The transaction and UTXO paths are not counted. */
    fun statsRequests(): List<String> = paths
        .filter { it.startsWith("/api/address/") && it.count { c -> c == '/' } == 3 }
        .map { it.substringAfterLast('/') }

    /** The history requests for [addresses], in the order they were asked. The two chains run at once, so each is checked on its own. */
    fun askedFor(addresses: List<String>): List<String> = statsRequests().filter { it in addresses }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.target
        paths += path
        queued.poll()?.let { return it }
        if (status != 200) return MockResponse.Builder().code(status).build()
        val parts = path.split("/")
        val address = parts.getOrNull(3)
        if (address != null && address in failing) return MockResponse.Builder().code(500).build()
        if (address != null && parts.getOrNull(4) == "utxo" && address in failingUtxos) {
            return MockResponse.Builder().code(500).build()
        }
        val body = when {
            path == "/api/blocks/tip/height" -> tip
            path == "/api/fee-estimates" -> FEES
            parts.size == 4 && parts[1] == "api" && parts[2] == "address" -> stats[parts[3]] ?: addressJson()
            parts.size == 5 && parts[2] == "address" && parts[4] == "txs" -> txs[parts[3]] ?: "[]"
            parts.size == 5 && parts[2] == "address" && parts[4] == "utxo" -> utxos[parts[3]] ?: "[]"
            else -> return MockResponse.Builder().code(404).build()
        }
        return MockResponse.Builder().setHeader("Content-Type", "application/json").body(body).build()
    }

    private companion object {
        const val FEES = """{"1":87.5,"6":12.25,"144":1.0}"""
    }
}
