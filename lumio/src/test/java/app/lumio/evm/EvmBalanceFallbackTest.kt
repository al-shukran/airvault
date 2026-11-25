package app.lumio.evm

import app.lumio.data.FakeClock
import app.lumio.data.config.ProviderConfig
import app.lumio.data.provider.routescan.RoutescanProvider
import app.lumio.data.rpc.CustomRpc
import app.lumio.data.rpc.JsonRpc
import app.lumio.data.sched.Scheduler
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * An EVM balance read through the pool, which offers the DATA provider first and the RPC provider after it. The DATA provider is Routescan on one
 * local MockWebServer, and the RPC provider is the Ethereum address of the second, so no test reaches a network. A server with no answer left
 * replies 500, so an unexpected call fails the test instead of hanging it.
 */
class EvmBalanceFallbackTest {
    private val data = MockWebServer()
    private val rpc = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val chain = EvmChain(chainId = 1L, name = "Ethereum", symbol = "ETH", decimals = 18, testnet = false, explorerUrl = null)
    private val address = "0x" + "ab".repeat(20)

    @Before
    fun start() {
        data.start()
        rpc.start()
    }

    @After
    fun stop() {
        scope.cancel()
        data.close()
        rpc.close()
    }

    /** Answers the calls in order, then 500 for any call that has no answer left. */
    private fun answers(vararg responses: MockResponse): Dispatcher {
        val queue = ArrayDeque(responses.toList())
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = queue.removeFirstOrNull() ?: MockResponse.Builder().code(500).build()
        }
    }

    private fun dataBalance(wei: String) = MockResponse.Builder().body("""{"status":"1","message":"OK","result":"$wei"}""").build()

    private fun rpcBalance(wei: Int) = MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"result":"0x${wei.toString(16)}"}""").build()

    /**
     * The EVM reads with Routescan as the DATA provider and the owner's Ethereum RPC address as the RPC provider. With [preferRpc], balance reads
     * take the RPC-only pool, which has no Routescan.
     */
    private fun reads(clock: FakeClock = FakeClock(), preferRpc: Boolean = false): EvmReads {
        val configs = mapOf(
            "routescan" to ProviderConfig(baseUrl = data.url("/").toString()),
            CustomRpc.ID to ProviderConfig(chainUrls = mapOf("1" to rpc.url("/").toString())),
        )
        val http = JsonRpc.newHttp(timeoutMs = 2_000L)
        return EvmReads(
            pool = Scheduler(listOf(RoutescanProvider(), CustomRpc(allowLocalHttp = true)), { configs }, http, clock, Random(7), scope),
            newOwner = { Scheduler(emptyList(), { configs }, http, clock, Random(7), scope) },
            settings = { EvmSettings() },
            rpcOnly = Scheduler(listOf(CustomRpc(allowLocalHttp = true)), { configs }, http, clock, Random(7), scope),
            preferRpc = { preferRpc },
        )
    }

    @Test
    fun aBalanceComesFromTheDataProviderAndTheRpcProviderIsNotCalled() = runBlocking {
        data.dispatcher = answers(dataBalance("7"))
        rpc.dispatcher = answers()

        assertEquals(BigInteger.valueOf(7), reads().balance(chain, address))
        assertEquals(1, data.requestCount)
        assertEquals(0, rpc.requestCount)
    }

    @Test
    fun aRateLimitedDataProviderIsCooledAndTheRpcProviderAnswersInItsPlace() = runBlocking {
        data.dispatcher = answers(MockResponse.Builder().code(429).setHeader("Retry-After", "60").build())
        rpc.dispatcher = answers(rpcBalance(42), rpcBalance(42))
        val reads = reads()

        assertEquals(BigInteger.valueOf(42), reads.balance(chain, address))
        assertEquals(BigInteger.valueOf(42), reads.balance(chain, address))
        assertEquals(1, data.requestCount)
        assertEquals(2, rpc.requestCount)
    }

    @Test
    fun theDataProviderIsTriedAgainOnceItsCooldownHasPassed() = runBlocking {
        data.dispatcher = answers(
            MockResponse.Builder().code(429).setHeader("Retry-After", "60").build(),
            dataBalance("9"),
        )
        rpc.dispatcher = answers(rpcBalance(42))
        val clock = FakeClock()
        val reads = reads(clock)

        assertEquals(BigInteger.valueOf(42), reads.balance(chain, address))
        clock.now = 61_000L
        assertEquals(BigInteger.valueOf(9), reads.balance(chain, address))
        assertEquals(2, data.requestCount)
        assertEquals(1, rpc.requestCount)
    }

    @Test
    fun preferRpcSendsTheBalanceReadToTheRpcProviderOnly() = runBlocking {
        data.dispatcher = answers(dataBalance("7"))
        rpc.dispatcher = answers(rpcBalance(42))

        assertEquals(BigInteger.valueOf(42), reads(preferRpc = true).balance(chain, address))
        assertEquals(0, data.requestCount)
        assertEquals(1, rpc.requestCount)
    }
}
