package app.lumio.data.provider.algorand

import android.util.Log
import app.airvault.chains.algorand.AlgorandNetwork
import app.lensly.core.algorand.AlgoSignedTxn
import app.lumio.BuildConfig
import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.ALGORAND_TXID
import app.lumio.data.provider.AlgorandIndexed
import app.lumio.data.provider.AlgorandIndexedTx
import app.lumio.data.provider.AlgorandParams
import app.lumio.data.provider.AlgorandPendingInfo
import app.lumio.data.provider.AlgorandPendingTx
import app.lumio.data.provider.AlgorandSuggestedParams
import app.lumio.data.provider.AsaAssetInfo
import app.lumio.data.provider.AsaHolding
import app.lumio.data.provider.AsaHoldings
import app.lumio.data.provider.AsaInfo
import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.Ops
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.Trait
import app.lumio.data.provider.amount
import app.lumio.data.provider.answerObject
import app.lumio.data.provider.fetchText
import app.lumio.data.provider.noRedirects
import app.lumio.data.provider.providerBase
import app.lumio.data.provider.text
import app.lumio.data.provider.whole
import app.lumio.data.rpc.PUBLIC_LIMITS
import java.math.BigInteger
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request as HttpRequest
import okhttp3.RequestBody.Companion.toRequestBody

/** The Algorand testnet, the network Lumio reads by default. Its chain name is "testnet", as Algonode names it. */
val ALGORAND_TESTNET = NetworkId(Networks.ALGO_TESTNET, testnet = true)

/** The Algorand mainnet, with the chain name "mainnet". */
val ALGORAND_MAINNET = NetworkId(Networks.ALGO_MAINNET, testnet = false)

/** An Algorand address: 58 characters of the base32 alphabet, so a request never carries a path separator into a URL. */
private val ADDRESS = Regex("[A-Z2-7]{58}")

/**
 * The algod endpoints that Algonode serves, keyless. `GET /v2/accounts/{address}` answers the account with its `amount` in microAlgos, the base unit,
 * as a JSON number. The provider sends no key and only public data: an account's public address, the suggested parameters of a transaction, a signed
 * transaction that is already public once it is sent, the pool's state of a transaction, and what the indexer holds of it. A 429 is a
 * [ProviderError.RateLimited] with its Retry-After, so the scheduler cools the provider for that long and then asks again. [endpoints] maps each chain
 * name to its algod base address and [indexers] to its indexer base address. Tests replace both with a local server.
 */
class AlgorandAlgod(
    private val endpoints: Map<String, String> = ENDPOINTS,
    private val indexers: Map<String, String> = INDEXERS,
) : Provider {
    override val id: String = "algorand"
    override val kind: Kind = Kind.DATA
    override val traits: Set<Trait> = setOf(Trait.KEYLESS)

    override fun supports(net: NetworkId, op: OpKey, cfg: ProviderConfig): Boolean = op in OPS && baseOf(net) != null

    override fun defaultLimits(cfg: ProviderConfig): Limits = ALGORAND_LIMITS

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        if (!supports(req.net, req.op, cfg)) throw ProviderError.Unsupported()
        return when (req) {
            is NativeBalance -> balance(req, http) as T
            is AlgorandParams -> params(req, http) as T
            is AlgorandPendingTx -> pending(req, http) as T
            is AlgorandIndexedTx -> indexed(req, http) as T
            is Broadcast -> broadcast(req, http) as T
            is AsaHoldings -> holdings(req, http) as T
            is AsaAssetInfo -> assetInfo(req, http) as T
            else -> throw ProviderError.Unsupported()
        }
    }

    /** The `amount` of the account in microAlgos. A field that is missing, not a whole number, or negative is a malformed answer. */
    private suspend fun balance(req: NativeBalance, http: Http): BigInteger {
        if (!ADDRESS.matches(req.address)) throw ProviderError.NotFound()
        val url = urlOf(req.net, "v2", "accounts", req.address)
        return answerObject(send(http, get(url)))["amount"].amount()
    }

    /**
     * The assets the account holds, from the `assets` list of `GET /v2/accounts/{address}`. Each entry gives its `asset-id`, its `amount` in asset units
     * and whether it is `is-frozen`. An account that holds no assets has no list, which is no holdings.
     */
    private suspend fun holdings(req: AsaHoldings, http: Http): List<AsaHolding> {
        val answer = answerObject(send(http, get(urlOf(req.net, "v2", "accounts", req.address))))
        return (answer["assets"] as? JsonArray).orEmpty().map { entry ->
            val asset = entry as? JsonObject ?: throw ProviderError.Fatal(SHAPE)
            AsaHolding(
                assetId = asset["asset-id"].whole(1L, Long.MAX_VALUE).toULong(),
                amount = asset["amount"].amount(),
                frozen = (asset["is-frozen"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
    }

    /**
     * The parameters of an asset, from the `params` of `GET /v2/assets/{id}`. The decimals are a whole number from 0 to 19. The unit name and the name
     * may be empty, since the creator leaves them so. A missing `params` object is a malformed answer.
     */
    private suspend fun assetInfo(req: AsaAssetInfo, http: Http): AsaInfo {
        val answer = answerObject(send(http, get(urlOf(req.net, "v2", "assets", req.assetId.toString()))))
        val params = answer["params"] as? JsonObject ?: throw ProviderError.Fatal(SHAPE)
        return AsaInfo(
            decimals = params["decimals"].whole(0L, MAX_ASSET_DECIMALS).toInt(),
            unitName = (params["unit-name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            name = (params["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }

    /**
     * The parameters that algod suggests for the next transaction, from `GET /v2/transactions/params`. The genesis ID and hash name the network, and
     * the genesis hash must be 32 bytes. They must also be the pinned ones of the network that was asked for, so an endpoint that serves another
     * network is refused. Each number is a whole number, and a missing one is a malformed answer.
     */
    private suspend fun params(req: AlgorandParams, http: Http): AlgorandSuggestedParams {
        val answer = answerObject(send(http, get(urlOf(req.net, "v2", "transactions", "params"))))
        val genesisId = answer["genesis-id"].text()
        val genesisHash = answer["genesis-hash"].base64()
        if (genesisId.isEmpty() || genesisId.toByteArray().size > MAX_GENESIS_ID_BYTES || genesisHash.size != GENESIS_HASH_BYTES) {
            throw ProviderError.Fatal(SHAPE)
        }
        val network = if (req.net.testnet) AlgorandNetwork.TESTNET else AlgorandNetwork.MAINNET
        if (genesisId != network.genesisId || !genesisHash.contentEquals(network.genesisHash)) {
            throw ProviderError.Fatal("The endpoint serves another Algorand network.")
        }
        return AlgorandSuggestedParams(
            genesisId = genesisId,
            genesisHash = genesisHash,
            lastRound = answer["last-round"].whole(0L, Long.MAX_VALUE),
            feePerByte = answer["fee"].whole(0L, MAX_FEE),
            minFee = answer["min-fee"].whole(0L, MAX_FEE),
        )
    }

    /**
     * The pool's state of a transaction, from `GET /v2/transactions/pending/{txid}`. A transaction the pool does not hold answers 404, which is
     * [ProviderError.NotFound]: it is confirmed, or it was never accepted. A pending transaction has no confirmed round.
     */
    private suspend fun pending(req: AlgorandPendingTx, http: Http): AlgorandPendingInfo {
        val url = urlOf(req.net, "v2", "transactions", "pending", req.txid)
        val answer = answerObject(send(http, get(url), notFoundIsAnswer = true))
        return AlgorandPendingInfo(
            confirmedRound = answer["confirmed-round"]?.whole(0L, Long.MAX_VALUE) ?: 0L,
            poolError = (answer["pool-error"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }

    /**
     * What the indexer holds of a transaction. The indexer's round is read first: the indexer only moves forward, so a transaction it does not hold at
     * that round, and which is looked up after it, was not confirmed by it. The lookup is `GET /v2/transactions/{txid}`, and a transaction it does not
     * hold answers 404, which is [ProviderError.NotFound] and reads as no confirmed round.
     */
    private suspend fun indexed(req: AlgorandIndexedTx, http: Http): AlgorandIndexed {
        val indexerRound = answerObject(send(http, get(indexerUrl(req.net, "health"))))["round"].whole(0L, Long.MAX_VALUE)
        val confirmedRound = try {
            val transaction = answerObject(send(http, get(indexerUrl(req.net, "v2", "transactions", req.txid)), notFoundIsAnswer = true))["transaction"]
            (transaction as? JsonObject ?: throw ProviderError.Fatal(SHAPE))["confirmed-round"].whole(1L, Long.MAX_VALUE)
        } catch (e: ProviderError.NotFound) {
            null
        }
        return AlgorandIndexed(confirmedRound, indexerRound)
    }

    /**
     * Sends the signed transaction as msgpack, with `POST /v2/transactions`, and returns the transaction ID that algod answers with. A refusal by the
     * node is a 400, which is [ProviderError.Fatal]. A 400 that says the transaction is already in the ledger is a success, and answers with the ID
     * of the signed bytes.
     */
    private suspend fun broadcast(req: Broadcast, http: Http): String {
        val request = HttpRequest.Builder()
            .url(urlOf(req.net, "v2", "transactions"))
            .post(req.raw.toRequestBody(MSGPACK))
            .build()
        val txid = try {
            answerObject(send(http, request, broadcast = true))["txId"].text()
        } catch (e: ProviderError.AlreadyKnown) {
            AlgoSignedTxn.signedTxid(req.raw)
        }
        if (!ALGORAND_TXID.matches(txid)) throw ProviderError.Unreadable(SHAPE)
        return txid
    }

    /** The base address of [net], or null when this provider does not serve it. Only HTTPS is accepted, or plain HTTP to this machine. */
    private fun baseOf(net: NetworkId): HttpUrl? = if (net.isAlgo) providerBase(endpoints[net.name], "") else null

    /** The URL of [net]'s base address with [segments] added as path segments. Each segment is validated by the request that names it. */
    private fun urlOf(net: NetworkId, vararg segments: String): HttpUrl =
        checkNotNull(baseOf(net)).newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    /** The URL of [net]'s indexer with [segments] added as path segments. */
    private fun indexerUrl(net: NetworkId, vararg segments: String): HttpUrl =
        checkNotNull(if (net.isAlgo) providerBase(indexers[net.name], "") else null).newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private fun get(url: HttpUrl): HttpRequest = HttpRequest.Builder().url(url).get().build()

    /**
     * The answer text of [request], over the client without redirects. A 404 is an answer, not a failure, when [notFoundIsAnswer] is set. The
     * request is logged in debug builds with its host, its outcome and its time, never its path, since the path can name an address.
     */
    private suspend fun send(http: Http, request: HttpRequest, notFoundIsAnswer: Boolean = false, broadcast: Boolean = false): String {
        val started = System.nanoTime()
        return try {
            fetchText(http.noRedirects(), request, banMs = null, notFoundIsAnswer = notFoundIsAnswer, broadcast = broadcast)
                .also { logLine(request, "ok", started) }
        } catch (e: Throwable) {
            logLine(request, "error=${e.javaClass.simpleName}", started)
            throw e
        }
    }

    /** The genesis hash, which is base64 text. Text that is not base64 is a malformed answer. */
    private fun JsonElement?.base64(): ByteArray = try {
        Base64.getDecoder().decode(text())
    } catch (e: IllegalArgumentException) {
        throw ProviderError.Fatal(SHAPE)
    }

    /** Debug builds only: one line per request, with the host, the outcome and the time. The path is never logged, since it names an address. */
    private fun logLine(request: HttpRequest, outcome: String, started: Long) {
        if (BuildConfig.DEBUG) {
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.d(LOG_TAG, "host=${request.url.host} $outcome ms=$ms")
        }
    }

    companion object {
        /** The tag of the debug-build request log. */
        const val LOG_TAG = "LumioAlgorand"

        /** The public endpoint of each Algorand network, by chain name. */
        val ENDPOINTS: Map<String, String> = mapOf(
            "testnet" to "https://testnet-api.algonode.cloud",
            "mainnet" to "https://mainnet-api.algonode.cloud",
        )

        /** The public indexer of each Algorand network, by chain name. It keeps the confirmed transactions that the pool no longer holds. */
        val INDEXERS: Map<String, String> = mapOf(
            "testnet" to "https://testnet-idx.algonode.cloud",
            "mainnet" to "https://mainnet-idx.algonode.cloud",
        )

        /**
         * The operations this provider serves on an Algorand network: balances, asset holdings, asset parameters, suggested parameters, pending
         * transactions, indexed transactions and broadcasts.
         */
        private val OPS = setOf(
            Ops.ALGO_NATIVE_BALANCE, Ops.ALGO_TOKEN_BALANCES, Ops.ALGO_ASSET_INFO, Ops.ALGO_SUGGESTED_PARAMS, Ops.ALGO_PENDING_TX,
            Ops.ALGO_INDEXED_TX, Ops.ALGO_BROADCAST,
        )

        /** Algonode answers eight account reads in about the time of one (measured), so the balance reads of a wallet may run eight at a time. */
        private val ALGORAND_LIMITS = PUBLIC_LIMITS.copy(maxConcurrent = 8)

        private const val SHAPE = "The answer does not have the documented shape."
        private const val GENESIS_HASH_BYTES = 32
        private const val MAX_GENESIS_ID_BYTES = 128

        /** The most decimals an Algorand asset can have. */
        private const val MAX_ASSET_DECIMALS = 19L

        /** A fee is a count of microAlgos. No node suggests more than this, so a larger number is a malformed answer. */
        private const val MAX_FEE = 1_000_000_000_000L
        private val MSGPACK = "application/x-binary".toMediaType()
    }
}
