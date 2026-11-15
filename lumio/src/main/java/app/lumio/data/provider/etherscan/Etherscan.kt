package app.lumio.data.provider.etherscan

import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AddressTxHistory
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.Op
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.Trait
import app.lumio.data.provider.explorer.ExplorerTx
import app.lumio.data.provider.explorer.explorerBalance
import app.lumio.data.provider.explorer.explorerNormalTxs
import app.lumio.data.provider.explorer.explorerTokenTxs
import app.lumio.data.provider.explorer.involving
import app.lumio.data.provider.fetchText
import app.lumio.data.provider.noRedirects
import app.lumio.data.provider.providerBase
import java.math.BigInteger
import okhttp3.Request as HttpRequest

/**
 * Etherscan V2 as a DATA provider. One API covers many EVM chains, and the `chainid` parameter picks the chain. It needs a key, so without one the
 * plugin serves nothing and the router never offers it. The key goes in the `apikey` query parameter, the one way the docs name.
 *
 * The history is the `txlist` and `tokentx` actions of https://docs.etherscan.io/api-reference/endpoint/txlist and
 * https://docs.etherscan.io/api-reference/endpoint/tokentx, read on 2026-11-16. Both take `address`, `page`, `offset` and `sort`. Neither page states a
 * maximum page size. The txlist page gives no empty-result example, so an account with no matches is read as an empty answer only when the answer has
 * status "0" with an empty array as its `result`. The exact text of that empty answer is not on the page, so it is not matched.
 *
 * Sources, read on 2026-11-15:
 * - Endpoint, parameters and key: https://docs.etherscan.io/api-reference/endpoint/balance. It gives
 *   `GET https://api.etherscan.io/v2/api?chainid={chainId}&module=account&action=balance&address={address}&tag=latest&apikey={key}`. Its OpenAPI
 *   document defines `apikey` as a query-string security scheme.
 * - Limits: https://docs.etherscan.io/resources/rate-limits. The free tier is 3 calls a second and 100,000 a day, on selected chains only.
 * - Chains: https://docs.etherscan.io/supported-chains. [FREE_CHAINS] holds the mainnets that page marks "Free tier available", minus HyperEVM (999) and
 *   Abstract (2741), whose free access ends on 30 November 2026. The paid-only mainnets (BNB, Base, OP, Avalanche, Gnosis) are left out. A free key is
 *   refused on them, and a refused call would cost a request on every read.
 * - Answers: https://docs.etherscan.io/common-error-messages. A rate or usage cap comes as HTTP 200 with status "0", such as "Max rate limit reached".
 *   A probe on 2026-11-15 without a key answered HTTP 200 with `{"status":"0","message":"NOTOK","result":"Missing/Invalid API Key"}`.
 */
class EtherscanProvider : Provider {
    override val id: String = "etherscan"
    override val kind: Kind = Kind.DATA
    override val traits: Set<Trait> = setOf(Trait.KEY_REQUIRED)

    override fun supports(net: NetworkId, op: Op, cfg: ProviderConfig): Boolean =
        (op == Op.NATIVE_BALANCE || op == Op.TX_HISTORY) && net.family == Family.EVM && !net.testnet && net.chain in FREE_CHAINS &&
            keyOf(cfg) != null && providerBase(cfg.baseUrl, BASE) != null

    override fun defaultLimits(cfg: ProviderConfig): Limits = LIMITS

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        if (!supports(req.net, req.op, cfg)) throw ProviderError.Unsupported()
        return when (req) {
            is NativeBalance -> balance(req, cfg, http) as T
            is AddressTxHistory -> txHistory(req, cfg, http) as T
            else -> throw ProviderError.Unsupported()
        }
    }

    /**
     * The page of history: the normal transactions of the account, then its ERC-20 transfers. The second request is charged to the call's rate budget
     * before it is sent, so the two together stay within the limit.
     */
    private suspend fun txHistory(req: AddressTxHistory, cfg: ProviderConfig, http: Http): List<ExplorerTx> {
        val normal = explorerNormalTxs(fetchText(http.noRedirects(), historyRequest(req, cfg, "txlist"), banMs = null))
        http.charge()
        val tokens = explorerTokenTxs(fetchText(http.noRedirects(), historyRequest(req, cfg, "tokentx"), banMs = null))
        val rows: List<ExplorerTx> = normal + tokens
        return rows.involving(req.address)
    }

    private fun historyRequest(req: AddressTxHistory, cfg: ProviderConfig, action: String): HttpRequest {
        val url = checkNotNull(providerBase(cfg.baseUrl, BASE)).newBuilder()
            .addPathSegment("v2")
            .addPathSegment("api")
            .addQueryParameter("chainid", req.net.chain)
            .addQueryParameter("module", "account")
            .addQueryParameter("action", action)
            .addQueryParameter("address", req.address)
            .addQueryParameter("page", req.page.toString())
            .addQueryParameter("offset", req.offset.toString())
            .addQueryParameter("sort", "desc")
            .addQueryParameter("apikey", checkNotNull(keyOf(cfg)))
            .build()
        return HttpRequest.Builder().url(url).get().build()
    }

    private suspend fun balance(req: NativeBalance, cfg: ProviderConfig, http: Http): BigInteger {
        if (!EVM_ADDRESS.matches(req.address)) throw ProviderError.NotFound()
        val url = checkNotNull(providerBase(cfg.baseUrl, BASE)).newBuilder()
            .addPathSegment("v2")
            .addPathSegment("api")
            .addQueryParameter("chainid", req.net.chain)
            .addQueryParameter("module", "account")
            .addQueryParameter("action", "balance")
            .addQueryParameter("address", req.address)
            .addQueryParameter("tag", "latest")
            .addQueryParameter("apikey", checkNotNull(keyOf(cfg)))
            .build()
        return explorerBalance(fetchText(http.noRedirects(), HttpRequest.Builder().url(url).get().build(), banMs = null))
    }

    private fun keyOf(cfg: ProviderConfig): String? = cfg.apiKey?.trim()?.takeIf { it.isNotEmpty() }
}

private const val BASE = "https://api.etherscan.io/"

/**
 * The mainnets that https://docs.etherscan.io/supported-chains marks "Free tier available", by chain ID, without HyperEVM (999) and Abstract (2741).
 */
private val FREE_CHAINS: Set<String> = setOf(
    "1", "137", "42161", "59144", "42220", "5000", "146", "130", "80094", "143", "9745", "4326", "1329", "988", "252", "480", "167000", "747474",
    "4352", "199", "50",
)

private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

/** The free tier is 3 calls a second, so this stays under that. */
private val LIMITS = Limits(rps = 2.0, burst = 2, maxConcurrent = 2)
