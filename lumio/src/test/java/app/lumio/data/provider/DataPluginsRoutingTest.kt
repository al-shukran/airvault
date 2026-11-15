package app.lumio.data.provider

import app.lumio.data.ETH
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.route.ProviderState
import app.lumio.data.route.Router
import app.lumio.data.rpc.RpcPlugins
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** The order in which the EVM pool offers providers for a native balance: the DATA providers of [DataPlugins], then the RPC pool. */
class DataPluginsRoutingTest {
    private val everyone = object : ProviderState {
        override fun available(providerId: String, nowMs: Long): Boolean = true

        override fun score(providerId: String, nowMs: Long): Double = 0.0
    }

    private val dataIds: Set<String> = DataPlugins.evm().map { it.id }.toSet()
    private val rpcIds: Set<String> = RpcPlugins.pool().map { it.id }.toSet()

    private fun offered(net: NetworkId = ETH, configs: Map<String, ProviderConfig> = emptyMap()): List<String> =
        Router(DataPlugins.evm() + RpcPlugins.pool())
            .route(net, Op.NATIVE_BALANCE, configs, everyone, nowMs = 0L, tried = emptySet(), excluded = emptySet())
            .map { it.id }

    @Test
    fun theDataProvidersThatServeEthereumAreOfferedBeforeAnyRpcProvider() {
        val order = offered()

        assertEquals(setOf("routescan", "blockscout", "blockchair", "3xpl"), order.filter { it in dataIds }.toSet())
        assertTrue(order.indexOfLast { it in dataIds } < order.indexOfFirst { it in rpcIds })
    }

    @Test
    fun etherscanIsOfferedFirstOnlyOnceItHasAKey() {
        assertFalse("etherscan" in offered())

        val keyed = offered(configs = mapOf("etherscan" to ProviderConfig(apiKey = "fake-key")))

        assertTrue("etherscan" in keyed)
        assertTrue(keyed.indexOf("etherscan") < keyed.indexOfFirst { it in rpcIds })
    }

    @Test
    fun aChainNoDataProviderServesIsReadFromTheRpcPoolOnly() {
        val base = NetworkId(Family.EVM, "8453", testnet = false)
        val configs = mapOf("etherscan" to ProviderConfig(apiKey = "fake-key"))

        val order = offered(base, configs)

        assertTrue(order.isNotEmpty())
        assertTrue(order.none { it in dataIds })
    }

    @Test
    fun preferRpcTakesTheExplorersOutOfABalanceRead() {
        assertTrue(DataPlugins.evm(preferRpc = true).isEmpty())

        val order = Router(DataPlugins.evm(preferRpc = true) + RpcPlugins.pool())
            .route(ETH, Op.NATIVE_BALANCE, emptyMap(), everyone, nowMs = 0L, tried = emptySet(), excluded = emptySet())
            .map { it.id }

        assertTrue(order.isNotEmpty())
        assertTrue(order.all { it in rpcIds })
    }
}
