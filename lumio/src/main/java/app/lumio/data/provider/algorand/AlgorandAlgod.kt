package app.lumio.data.provider.algorand

import android.util.Log
import app.lumio.BuildConfig
import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.Family
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
import app.lumio.data.provider.fetchText
import app.lumio.data.provider.noRedirects
import app.lumio.data.provider.providerBase
import app.lumio.data.rpc.PUBLIC_LIMITS
import java.math.BigInteger
import okhttp3.HttpUrl
import okhttp3.Request as HttpRequest

/** The Algorand testnet, the network Lumio reads by default. Its chain name is "testnet", as Algonode names it. */
val ALGORAND_TESTNET = NetworkId(Family.ALGO, "testnet", testnet = true)

/** The Algorand mainnet, with the chain name "mainnet". */
val ALGORAND_MAINNET = NetworkId(Family.ALGO, "mainnet", testnet = false)

/** An Algorand address: 58 characters of the base32 alphabet, so a request never carries a path separator into a URL. */
private val ADDRESS = Regex("[A-Z2-7]{58}")

/**
 * The algod endpoints that Algonode serves, keyless. `GET /v2/accounts/{address}` answers the account with its `amount` in microAlgos, the base unit,
 * as a JSON number. The provider sends no key and only a public address, and it serves the native balance and nothing else. A 429 is a
 * [ProviderError.RateLimited] with its Retry-After, so the scheduler cools the provider for that long. [endpoints] maps each chain name to its base
 * address. Tests replace it with a local server.
 */
class AlgorandAlgod(private val endpoints: Map<String, String> = ENDPOINTS) : Provider {
    override val id: String = "algorand"
    override val kind: Kind = Kind.DATA
    override val traits: Set<Trait> = setOf(Trait.KEYLESS)

    override fun supports(net: NetworkId, op: Op, cfg: ProviderConfig): Boolean = op == Op.NATIVE_BALANCE && baseOf(net) != null

    override fun defaultLimits(cfg: ProviderConfig): Limits = PUBLIC_LIMITS

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> execute(req: Request<T>, cfg: ProviderConfig, http: Http): T {
        if (!supports(req.net, req.op, cfg)) throw ProviderError.Unsupported()
        return when (req) {
            is NativeBalance -> balance(req, http) as T
            else -> throw ProviderError.Unsupported()
        }
    }

    /** The `amount` of the account in microAlgos. A field that is missing, not a whole number, or negative is a malformed answer. */
    private suspend fun balance(req: NativeBalance, http: Http): BigInteger {
        if (!ADDRESS.matches(req.address)) throw ProviderError.NotFound()
        val url = checkNotNull(baseOf(req.net)).newBuilder()
            .addPathSegment("v2")
            .addPathSegment("accounts")
            .addPathSegment(req.address)
            .build()
        val request = HttpRequest.Builder().url(url).get().build()
        val started = System.nanoTime()
        val text = try {
            fetchText(http.noRedirects(), request, banMs = null).also { logLine(request, "ok", started) }
        } catch (e: Throwable) {
            logLine(request, "error=${e.javaClass.simpleName}", started)
            throw e
        }
        return answerObject(text)["amount"].amount()
    }

    /** The base address of [net], or null when this provider does not serve it. Only HTTPS is accepted, or plain HTTP to this machine. */
    private fun baseOf(net: NetworkId): HttpUrl? = if (net.family == Family.ALGO) providerBase(endpoints[net.chain], "") else null

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
    }
}
