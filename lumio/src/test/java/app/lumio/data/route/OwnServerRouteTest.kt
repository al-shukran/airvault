package app.lumio.data.route

import app.lumio.data.config.ProviderConfig
import app.lumio.data.esplora.BlockstreamProvider
import app.lumio.data.esplora.CustomEsploraProvider
import app.lumio.data.esplora.MempoolProvider
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Op
import kotlin.test.assertEquals
import org.junit.Test

/** The owner's own server takes its network for itself, so no address reaches a public server while it is enabled. */
class OwnServerRouteTest {
    private val net = NetworkId(Family.BTC, "testnet4", testnet = true)
    private val router = Router(
        listOf(
            MempoolProvider(bases = mapOf("testnet4" to "https://mempool.example.org/api")),
            BlockstreamProvider(bases = mapOf("testnet4" to "https://blockstream.example.org/testnet4/api")),
            CustomEsploraProvider("testnet4"),
        ),
    )
    private val ownUrl = mapOf("custom-btc-testnet4" to ProviderConfig(baseUrl = "https://node.example.org/api"))

    private fun state(unavailable: Set<String> = emptySet()) = object : ProviderState {
        override fun available(providerId: String, nowMs: Long): Boolean = providerId !in unavailable

        override fun score(providerId: String, nowMs: Long): Double = 0.0
    }

    private fun routed(configs: Map<String, ProviderConfig>, state: ProviderState): List<String> =
        router.route(net, Op.ADDRESS_STATS, configs, state, nowMs = 0L, tried = emptySet(), excluded = emptySet()).map { it.id }

    @Test
    fun anEnabledOwnServerIsTheOnlyCandidateOfItsNetwork() {
        assertEquals(listOf("custom-btc-testnet4"), routed(ownUrl, state()))
    }

    @Test
    fun withoutAnOwnServerThePublicServersServe() {
        assertEquals(setOf("mempool", "blockstream"), routed(emptyMap(), state()).toSet())
    }

    @Test
    fun aDisabledOwnServerLetsThePublicServersServe() {
        val disabled = mapOf("custom-btc-testnet4" to ProviderConfig(enabled = false, baseUrl = "https://node.example.org/api"))

        assertEquals(setOf("mempool", "blockstream"), routed(disabled, state()).toSet())
    }

    @Test
    fun aCoolingOwnServerGivesNoCandidateRatherThanAPublicOne() {
        assertEquals(emptyList(), routed(ownUrl, state(unavailable = setOf("custom-btc-testnet4"))))
    }
}
