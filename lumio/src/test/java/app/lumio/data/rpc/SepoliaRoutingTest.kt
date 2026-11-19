package app.lumio.data.rpc

import app.lumio.data.MonotonicClock
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativeBalance
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * A Sepolia balance read through the whole public pool, with no network. 1RPC's free plan refuses Sepolia with JSON-RPC error 35, and the
 * other providers answer. The read must fall through to a provider that serves the chain, and must not end in an error.
 */
class SepoliaRoutingTest {
    private val sepolia = NetworkId(Networks.evm(11155111), testnet = true)
    private val holder = "0x" + "1".repeat(40)

    /** Answers every host without a network: 1RPC refuses Sepolia, and every other host answers a balance of 42. */
    private val fake = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.host == "public.1rpc.io") REFUSED_ON_FREE_PLAN else BALANCE_OF_42
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    @Test
    fun aSepoliaBalanceComesFromAProviderThatServesSepolia() = runBlocking {
        val scheduler = Scheduler(
            providers = RpcPlugins.pool(),
            configs = { emptyMap() },
            http = Http(fake),
            clock = MonotonicClock,
            random = Random(7),
            scope = this,
        )

        assertEquals(BigInteger.valueOf(42), scheduler.run(NativeBalance(sepolia, holder), Priority.FOREGROUND))
    }

    private companion object {
        const val REFUSED_ON_FREE_PLAN =
            """{"jsonrpc":"2.0","id":1,"error":{"code":35,"message":"chain is not available on free plan, please upgrade to paid plan"}}"""
        const val BALANCE_OF_42 = """{"jsonrpc":"2.0","id":1,"result":"0x2a"}"""
    }
}
