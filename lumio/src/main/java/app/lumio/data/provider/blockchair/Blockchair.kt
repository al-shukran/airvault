package app.lumio.data.provider.blockchair

import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AddressUtxos
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.Op
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.Trait
import app.lumio.data.provider.Utxo
import app.lumio.data.provider.amount
import app.lumio.data.provider.answerObject
import app.lumio.data.provider.asArray
import app.lumio.data.provider.asObject
import app.lumio.data.provider.fetchText
import app.lumio.data.provider.hexHash
import app.lumio.data.provider.noRedirects
import app.lumio.data.provider.providerBase
import app.lumio.data.provider.whole
import java.math.BigInteger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.Request as HttpRequest

/**
 * Blockchair as a DATA provider. It serves the Bitcoin mainnet unspent outputs and the Ethereum mainnet native balance. It is keyless by
 * default, and it takes an optional key from the config. [fallbackOnly] marks it FALLBACK_ONLY, for the Bitcoin scheduler, which offers it only when
 * no other provider can serve the read at that moment. The EVM pool keeps it as an ordinary DATA provider.
 *
 * Sources, read on 2026-11-15:
 * - Official: https://blockchair.com/api/docs and https://blockchair.com/api/plans. Both answer HTTP 401 with a browser challenge to a client
 *   that is not a browser, so this file was not checked against them. Nothing here is quoted from them.
 * - A community changelog, dated 2021, for the path, the field names and the `key` parameter:
 *   https://github.com/jjlittlezz/Blockchair.Support/blob/master/API.md
 * - The live API host, https://api.blockchair.com, answered HTTP 430 with `{"data":null,"context":{"code":430,...}}` to a keyless address request
 *   (2026-11-15), which is the quota response that this file treats as a rate limit.
 *
 * Requests: `GET {base}/{chain}/dashboards/address/{address}`, where `chain` is `bitcoin` or `ethereum`. Output pages use `limit=0,N` and
 * `offset=0,M`. The key, when set, goes in the `key` query parameter.
 *
 * Not supported: Bitcoin testnets and Sepolia, which the changelog does not give as current. Address statistics (ADDRESS_STATS), because the
 * dashboard's chain and mempool sides are not documented in a form that could be split without guessing.
 */
class BlockchairProvider(fallbackOnly: Boolean = false) : Provider {
    override val id: String = "blockchair"
    override val kind: Kind = Kind.DATA
    override val traits: Set<Trait> =
        if (fallbackOnly) setOf(Trait.KEYLESS, Trait.KEY_OPTIONAL, Trait.FALLBACK_ONLY) else setOf(Trait.KEYLESS, Trait.KEY_OPTIONAL)

    override fun supports(net: NetworkId, op: Op, cfg: ProviderConfig): Boolean =
        chainOf(net, op) != null && providerBase(cfg.baseUrl, BASE) != null

    override fun defaultLimits(cfg: ProviderConfig): Limits = if (keyOf(cfg) == null) KEYLESS_LIMITS else KEYED_LIMITS

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        if (!supports(req.net, req.op, cfg)) throw ProviderError.Unsupported()
        return when (req) {
            is NativeBalance -> balance(req, cfg, http) as T
            is AddressUtxos -> utxos(req, cfg, http) as T
            else -> throw ProviderError.Unsupported()
        }
    }

    /** The Blockchair chain name for [op] on [net], or null when this provider does not serve it. */
    private fun chainOf(net: NetworkId, op: Op): String? = when {
        op == Op.ADDRESS_UTXOS && net.family == Family.BTC && net.chain == "mainnet" && !net.testnet -> "bitcoin"
        op == Op.NATIVE_BALANCE && net.family == Family.EVM && net.chain == "1" && !net.testnet -> "ethereum"
        else -> null
    }

    private suspend fun balance(req: NativeBalance, cfg: ProviderConfig, http: Http): BigInteger {
        if (!EVM_ADDRESS.matches(req.address)) throw ProviderError.NotFound()
        val root = answerObject(fetch(http, cfg, "ethereum", req.address, emptyMap()))
        return row(root)["address"].asObject()["balance"].amount()
    }

    /**
     * Every unspent output of the address. Pages are read until one comes back empty, so a server that answers fewer rows than asked
     * cannot end the list early. A set longer than [MAX_PAGES] pages is refused rather than cut short. The scheduler paid for the first page's
     * request, so each later page is charged before it is sent: N pages cost N tokens of the keyless budget.
     */
    private suspend fun utxos(req: AddressUtxos, cfg: ProviderConfig, http: Http): List<Utxo> {
        if (!BTC_ADDRESS.matches(req.address)) throw ProviderError.NotFound()
        val all = ArrayList<Utxo>()
        var offset = 0
        repeat(MAX_PAGES) { index ->
            if (index > 0) http.charge()
            val page = fetch(http, cfg, "bitcoin", req.address, mapOf("limit" to "0,$PAGE", "offset" to "0,$offset"))
            val rows = row(answerObject(page))["utxo"].asArray()
            if (rows.isEmpty()) return all
            rows.forEach { all += utxoOf(it) }
            offset += rows.size
        }
        throw ProviderError.Fatal("The address has more outputs than one read holds.")
    }

    private fun utxoOf(element: JsonElement): Utxo {
        val output = element.asObject()
        return Utxo(
            txid = output["transaction_hash"].hexHash(),
            vout = output["index"].whole(0, Int.MAX_VALUE.toLong()).toInt(),
            valueSats = output["value"].whole(0, Long.MAX_VALUE),
            blockHeight = output["block_id"].whole(-1, Long.MAX_VALUE).takeIf { it >= 0 },
        )
    }

    /** The one address's entry in `data`, which Blockchair keys by the address. */
    private fun row(root: JsonObject): JsonObject = root["data"].asObject().values.firstOrNull().asObject()

    private suspend fun fetch(http: Http, cfg: ProviderConfig, chain: String, address: String, query: Map<String, String>): String {
        val url: HttpUrl = checkNotNull(providerBase(cfg.baseUrl, BASE)).newBuilder()
            .addPathSegment(chain)
            .addPathSegment("dashboards")
            .addPathSegment("address")
            .addPathSegment(address)
            .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
            .apply { keyOf(cfg)?.let { addQueryParameter("key", it) } }
            .build()
        return fetchText(http.noRedirects(), HttpRequest.Builder().url(url).get().build(), BAN_MS)
    }

    private fun keyOf(cfg: ProviderConfig): String? = cfg.apiKey?.trim()?.takeIf { it.isNotEmpty() }
}

private const val BASE = "https://api.blockchair.com/"

/** Output rows per page. The documented maximum is 10,000, and a smaller page keeps each answer modest. */
private const val PAGE = 1_000

/** The most output pages one call reads: 50,000 outputs at [PAGE] rows. A longer set is refused, never cut short. */
private const val MAX_PAGES = 50

/** The one-hour ban that the changelog documents for HTTP 429. It applies when the answer has no Retry-After. */
private const val BAN_MS = 3_600_000L

private val BTC_ADDRESS = Regex("[A-Za-z0-9]{14,100}")
private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

/** About 960 calls a day, under the 1,000 a day that the free keyless tier is reported to allow. One call at a time. */
private val KEYLESS_LIMITS = Limits(rps = 1.0 / 90, burst = 1, maxConcurrent = 1)

/** A keyed plan's allowance is not read from the docs, so this stays modest. */
private val KEYED_LIMITS = Limits(rps = 1.0, burst = 3, maxConcurrent = 2)
