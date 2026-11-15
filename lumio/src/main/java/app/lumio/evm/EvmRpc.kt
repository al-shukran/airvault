package app.lumio.evm

import app.lumio.data.provider.ProviderError
import app.lumio.data.rpc.EvmCalls
import app.lumio.data.rpc.JsonRpc
import okhttp3.OkHttpClient

/** A read that Lumio could not complete. The message is for the owner, and it holds no key and no address. */
class RpcFailure(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { UNREACHABLE, REFUSED, MALFORMED, ERROR, WRONG_CHAIN }
}

/** The owner's words for a failed provider call. */
internal fun ProviderError.toRpcFailure(): RpcFailure = when (this) {
    is ProviderError.RateLimited -> RpcFailure(RpcFailure.Reason.UNREACHABLE, "The endpoint is rate limited. Try again in a moment.")
    is ProviderError.Transient -> RpcFailure(RpcFailure.Reason.UNREACHABLE, "The endpoint did not answer.")
    is ProviderError.Unsupported -> RpcFailure(RpcFailure.Reason.REFUSED, "No RPC address serves this chain. Set one in EVM settings.")
    is ProviderError.NotFound -> RpcFailure(RpcFailure.Reason.ERROR, "The endpoint has no answer for this request.")
    is ProviderError.Fatal -> RpcFailure(RpcFailure.Reason.ERROR, message ?: "The endpoint failed.")
}

/**
 * The chain check for an RPC address that the owner enters in EVM settings. The address must answer eth_chainId with the chain the owner named,
 * before Lumio saves it. Balances and sends read through [EvmReads], so this is the only direct call. It runs on the shared JSON-RPC client, and
 * plain HTTP is accepted only in a debug build, see [app.lumio.data.rpc.RpcUrl].
 */
class EvmRpc(
    /** True only in a debug build: plain HTTP to the emulator's host address or to localhost. */
    val allowLocalHttp: Boolean,
    timeoutMs: Long = JsonRpc.DEFAULT_TIMEOUT_MS,
) {
    private val client: OkHttpClient = JsonRpc.newHttp(timeoutMs).client

    /** Checks that the endpoint at [url] serves chain [expected]. Lumio asks this before it saves an owner's address. */
    fun requireChain(url: String, expected: Long) {
        val actual = try {
            EvmCalls.chainId(client, url, allowLocalHttp)
        } catch (e: ProviderError) {
            throw e.toRpcFailure()
        }
        if (actual != expected) throw RpcFailure(RpcFailure.Reason.WRONG_CHAIN, "The endpoint reports chain $actual, not $expected.")
    }
}
