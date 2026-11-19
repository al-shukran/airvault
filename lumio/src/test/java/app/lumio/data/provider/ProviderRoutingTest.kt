package app.lumio.data.provider

import app.lumio.data.BTC
import app.lumio.data.ETH
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.blockchair.BlockchairProvider
import app.lumio.data.provider.threexpl.ThreeXplProvider
import app.lumio.data.route.ProviderState
import app.lumio.data.route.Router
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** Which of the two data plugins the router offers for each network and op. A network a plugin does not serve never reaches it. */
class ProviderRoutingTest {
    private val router = Router(listOf(BlockchairProvider(), ThreeXplProvider()))

    private val everyone = object : ProviderState {
        override fun available(providerId: String, nowMs: Long): Boolean = true

        override fun score(providerId: String, nowMs: Long): Double = 0.0
    }

    private fun ids(net: NetworkId, op: OpKey): Set<String> =
        router.route(net, op, emptyMap(), everyone, 0L, emptySet(), emptySet()).map { it.id }.toSet()

    @Test
    fun bitcoinOutputsGoOnlyToBlockchair() {
        assertEquals(setOf("blockchair"), ids(BTC, Ops.BTC_UTXOS))
    }

    @Test
    fun ethereumMainnetBalancesGoToBothPlugins() {
        assertEquals(setOf("blockchair", "3xpl"), ids(ETH, OpKey.on(ETH, "nativeBalance")))
    }

    @Test
    fun aNetworkNeitherPluginServesHasNoCandidate() {
        assertTrue(ids(NetworkId(Networks.evm(11155111), testnet = true), OpKey.on(NetworkId(Networks.evm(11155111), testnet = true), "nativeBalance")).isEmpty())
        assertTrue(ids(NetworkId(Networks.BTC_TESTNET4, testnet = true), Ops.BTC_UTXOS).isEmpty())
        assertTrue(ids(BTC, Ops.BTC_ADDRESS_STATS).isEmpty())
    }
}
