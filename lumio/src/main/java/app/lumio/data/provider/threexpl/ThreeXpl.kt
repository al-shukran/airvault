package app.lumio.data.provider.threexpl

import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.Op
import app.lumio.data.provider.Provider
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.Trait
import app.lumio.data.provider.amount
import app.lumio.data.provider.answerObject
import app.lumio.data.provider.asObject
import app.lumio.data.provider.fetchText
import app.lumio.data.provider.noRedirects
import app.lumio.data.provider.providerBase
import java.math.BigInteger
import okhttp3.HttpUrl
import okhttp3.Request as HttpRequest

/**
 * 3xpl as a DATA provider. It serves the Ethereum mainnet native balance. It works without a key on the sandbox host, and with a key on the
 * production host.
 *
 * Sources, read on 2026-11-15:
 * - Official introduction: https://3xpl.com/data/json-api. It names the production host `https://api.3xpl.com`, which needs a key, and the sandbox
 *   `https://sandbox-api.3xpl.com`, which needs no key, is rate limited, and has no uptime guarantee. The key goes in
 *   `Authorization: Bearer {token}`, or in a `token` query parameter. This file uses the header, so a key never sits in a logged URL.
 * - Official reference: https://3xpl.com/data/json-api/docs (read in part). It gives `GET /{blockchain}/address/{address}` with `data` and `from`
 *   parameters, and `balances[module][currency].balance`, where the module for Ethereum mainnet is `ethereum-main`.
 * - The sandbox, checked live on 2026-11-15, returned `{"data":{"balances":{"ethereum-main":{"ethereum":{"balance":"5798969845714110716",...`
 *   for `GET /ethereum/address/{address}?data=balances&from=ethereum-main`. The currency key is `ethereum`, and the balance is a decimal integer
 *   string, which this file reads as wei.
 * - https://docs.3xpl.com/ presented an expired TLS certificate when checked, so it was not used.
 *
 * The docs give no status code for quota or rate limits, and no key allowance. HTTP 402, 429 and 430 are therefore read as rate limits
 * under the rule for this slice, not from 3xpl's own text.
 *
 * Not supported: Bitcoin. The docs list Bitcoin balances, but give no UTXO list and no chain and mempool statistics, so neither op can be
 * served exactly. Other EVM chains and testnets are not named in the docs that were read.
 */
class ThreeXplProvider : Provider {
    override val id: String = "3xpl"
    override val kind: Kind = Kind.DATA
    override val traits: Set<Trait> = setOf(Trait.KEYLESS, Trait.KEY_OPTIONAL)

    override fun supports(net: NetworkId, op: Op, cfg: ProviderConfig): Boolean =
        op == Op.NATIVE_BALANCE && net.isEvm && net.name == "1" && !net.testnet && endpointOf(cfg) != null

    override fun defaultLimits(cfg: ProviderConfig): Limits = if (keyOf(cfg) == null) SANDBOX_LIMITS else KEYED_LIMITS

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        if (!supports(req.net, req.op, cfg)) throw ProviderError.Unsupported()
        return when (req) {
            is NativeBalance -> balance(req, cfg, http) as T
            else -> throw ProviderError.Unsupported()
        }
    }

    private suspend fun balance(req: NativeBalance, cfg: ProviderConfig, http: Http): BigInteger {
        if (!EVM_ADDRESS.matches(req.address)) throw ProviderError.NotFound()
        val url: HttpUrl = checkNotNull(endpointOf(cfg)).newBuilder()
            .addPathSegment("ethereum")
            .addPathSegment("address")
            .addPathSegment(req.address)
            .addQueryParameter("data", "balances")
            .addQueryParameter("from", "ethereum-main")
            .build()
        val request = HttpRequest.Builder()
            .url(url)
            .get()
            .apply { keyOf(cfg)?.let { header("Authorization", "Bearer $it") } }
            .build()
        val root = answerObject(fetchText(http.noRedirects(), request, banMs = null))
        return root["data"].asObject()["balances"].asObject()["ethereum-main"].asObject()["ethereum"].asObject()["balance"].amount()
    }
}

/** The base URL for [cfg]: the owner's own base when one is set, else the production host with a key and the sandbox without one. */
internal fun endpointOf(cfg: ProviderConfig): HttpUrl? = providerBase(cfg.baseUrl, if (keyOf(cfg) == null) SANDBOX else PRODUCTION)

private fun keyOf(cfg: ProviderConfig): String? = cfg.apiKey?.trim()?.takeIf { it.isNotEmpty() }

private const val SANDBOX = "https://sandbox-api.3xpl.com/"
private const val PRODUCTION = "https://api.3xpl.com/"

private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

/** The sandbox states a rate limit but no figure, so this stays well under any plausible one. */
private val SANDBOX_LIMITS = Limits(rps = 0.5, burst = 2, maxConcurrent = 1)

/** No keyed allowance is read from the docs, so this stays modest. */
private val KEYED_LIMITS = Limits(rps = 1.0, burst = 3, maxConcurrent = 2)
