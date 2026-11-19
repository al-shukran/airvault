package app.lumio.data.provider.blockscout

import app.lumio.data.config.Limits
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AddressTxHistory
import app.lumio.data.provider.Http
import app.lumio.data.provider.Kind
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.Ops
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
import okhttp3.HttpUrl
import okhttp3.Request as HttpRequest

/**
 * Blockscout as a DATA provider. It serves the native balance and the transaction history of each chain in [BASES] from that chain's hosted instance.
 * An optional API key goes in the `apikey` query parameter, as the Blockscout docs show.
 *
 * Sources for the history, read on 2026-11-16: https://docs.blockscout.com/devs/apis/rpc/account. It gives
 * `GET {instance}/api?module=account&action=txlist` and `action=tokentx`, with `address`, `page`, `offset` and `sort`, and it states a maximum of 10,000
 * rows per query. Neither list names a log index, and the page documents no `apikey` for these two actions. The key is sent as it is for the balance.
 *
 * Sources, read on 2026-11-15:
 * - Endpoint and answer: https://docs.blockscout.com/devs/apis/rpc/account. It gives `GET {instance}/api?module=account&action=balance&address={address}`,
 *   answered by `{"message":"OK","result":"663046792267785498951364","status":"1"}`.
 * - Live probe on 2026-11-15: https://eth.blockscout.com/api answered that shape with a balance. An invalid address answered
 *   `{"message":"Invalid address hash","result":null,"status":"0"}`.
 * - Limits: https://docs.blockscout.com/devs/apis/requests-and-limits. The default for unkeyed traffic is 300 requests a minute per IP. The page does not
 *   say that figure applies to hosted instances, so the rate here stays well under it.
 * - Not listed: base.blockscout.com and arbitrum.blockscout.com answered with a bot-check page, and gnosis.blockscout.com and optimism.blockscout.com
 *   redirected to other domains. None of the four could be read, so none is mapped.
 * - Key: https://docs.blockscout.com/devs/blockscout-api.md shows the key as `apikey`, in the example URL
 *   `https://api.blockscout.com/v2/api?chain_id=1&module=account&action=balance&address=0x...&apikey=proapi_xxx`. That is the hosted multichain API, not the
 *   instance used here, so it is not verified that a key is accepted by the instance. The limits page says per-instance API access will be
 *   deprecated soon, so this plugin may need replacing.
 */
class BlockscoutProvider(private val bases: Map<String, String> = BASES) : Provider {
    override val id: String = "blockscout"
    override val kind: Kind = Kind.DATA
    override val traits: Set<Trait> = setOf(Trait.KEYLESS, Trait.KEY_OPTIONAL)

    override fun supports(net: NetworkId, op: OpKey, cfg: ProviderConfig): Boolean =
        (op == Ops.EVM_NATIVE_BALANCE || op == Ops.EVM_TX_HISTORY) && instanceOf(net) != null

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

    private suspend fun balance(req: NativeBalance, cfg: ProviderConfig, http: Http): BigInteger {
        if (!EVM_ADDRESS.matches(req.address)) throw ProviderError.NotFound()
        val url = checkNotNull(instanceOf(req.net)).newBuilder()
            .addPathSegment("api")
            .addQueryParameter("module", "account")
            .addQueryParameter("action", "balance")
            .addQueryParameter("address", req.address)
            .apply { cfg.apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("apikey", it) } }
            .build()
        return explorerBalance(fetchText(http.noRedirects(), HttpRequest.Builder().url(url).get().build(), banMs = null))
    }

    /**
     * The page of history: the normal transactions of the account, then its token transfers. The second request is charged to the call's rate budget
     * before it is sent, so the two together stay within the limit.
     */
    private suspend fun txHistory(req: AddressTxHistory, cfg: ProviderConfig, http: Http): List<ExplorerTx> {
        val normal = explorerNormalTxs(fetchText(http.noRedirects(), historyRequest(req, cfg, "txlist"), banMs = null))
        http.charge()
        val tokens = explorerTokenTxs(fetchText(http.noRedirects(), historyRequest(req, cfg, "tokentx"), banMs = null))
        val rows: List<ExplorerTx> = normal + tokens
        return rows.involving(req.address)
    }

    /** `{instance}/api?module=account&action={action}&address=...&page=...&offset=...&sort=desc`, with the key as `apikey` when one is set. */
    private fun historyRequest(req: AddressTxHistory, cfg: ProviderConfig, action: String): HttpRequest {
        val url = checkNotNull(instanceOf(req.net)).newBuilder()
            .addPathSegment("api")
            .addQueryParameter("module", "account")
            .addQueryParameter("action", action)
            .addQueryParameter("address", req.address)
            .addQueryParameter("page", req.page.toString())
            .addQueryParameter("offset", req.offset.toString())
            .addQueryParameter("sort", "desc")
            .apply { cfg.apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("apikey", it) } }
            .build()
        return HttpRequest.Builder().url(url).get().build()
    }

    /** The instance that serves [net], or null when Blockscout hosts none for it, or the address it has is not accepted. */
    private fun instanceOf(net: NetworkId): HttpUrl? =
        if (net.isEvm && !net.testnet) providerBase(bases[net.name], "") else null

    companion object {
        /** The hosted instance of each EVM mainnet, by chain ID. Only Ethereum was read on 2026-11-15, see the KDoc. */
        val BASES: Map<String, String> = mapOf("1" to "https://eth.blockscout.com")
    }
}

private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

/** Unkeyed traffic is limited to 300 requests a minute per IP, so this stays at one a second, one call at a time. */
private val LIMITS = Limits(rps = 1.0, burst = 2, maxConcurrent = 1)
