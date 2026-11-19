package app.lumio.data.route

import app.lumio.data.BTC
import app.lumio.data.ETH
import app.lumio.data.FakeProvider
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Kind
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.Ops
import app.lumio.data.provider.Provider
import app.lumio.data.provider.Trait
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which providers the router offers for a request, and in what order. */
class RouterTest {

    private val everyone = coolingState()

    /** A state in which the listed providers are cooling down and the rest are available. */
    private fun coolingState(vararg cooling: String): ProviderState = object : ProviderState {
        override fun available(providerId: String, nowMs: Long): Boolean = providerId !in cooling

        override fun score(providerId: String, nowMs: Long): Double = 0.0
    }

    private fun route(
        providers: List<Provider>,
        net: NetworkId = ETH,
        op: OpKey = Ops.BTC_TIP_HEIGHT,
        configs: Map<String, ProviderConfig> = emptyMap(),
        state: ProviderState = everyone,
        tried: Set<String> = emptySet(),
    ): List<String> =
        Router(providers).route(net, op, configs, state, nowMs = 0L, tried = tried, excluded = emptySet()).map { it.id }

    @Test
    fun aProviderThatDoesNotServeTheNetworkOrTheOperationIsNeverOffered() {
        val a = FakeProvider("a", networks = setOf(ETH), ops = setOf(Ops.BTC_TIP_HEIGHT))

        assertEquals(emptyList<String>(), route(listOf(a), net = BTC))
        assertEquals(emptyList<String>(), route(listOf(a), op = Ops.EVM_NATIVE_BALANCE))
        assertEquals(listOf("a"), route(listOf(a)))
    }

    @Test
    fun aKeyRequiredProviderIsOfferedOnlyWithAKey() {
        val a = FakeProvider("a", traits = setOf(Trait.KEY_REQUIRED))

        assertEquals(emptyList<String>(), route(listOf(a)))
        assertEquals(listOf("a"), route(listOf(a), configs = mapOf("a" to ProviderConfig(apiKey = "fake-key"))))
    }

    @Test
    fun aDisabledProviderIsNotOffered() {
        val a = FakeProvider("a")

        assertEquals(emptyList<String>(), route(listOf(a), configs = mapOf("a" to ProviderConfig(enabled = false))))
    }

    @Test
    fun aCoolingOrOpenProviderIsNotOffered() {
        assertEquals(listOf("b"), route(listOf(FakeProvider("a"), FakeProvider("b")), state = coolingState("a")))
    }

    @Test
    fun aLowerPriorityNumberIsOfferedFirst() {
        val configs = mapOf("a" to ProviderConfig(priority = 1), "b" to ProviderConfig(priority = 0))

        assertEquals(listOf("b", "a"), route(listOf(FakeProvider("a"), FakeProvider("b")), configs = configs))
    }

    @Test
    fun aProviderThatFailedTransientlyGoesLastSoARetryMovesOn() {
        assertEquals(listOf("b", "a"), route(listOf(FakeProvider("a"), FakeProvider("b")), tried = setOf("a")))
    }

    @Test
    fun aBroadcastOnEvmOffersOnlyMevProtectedProvidersWhileOneIsHealthy() {
        val plain = FakeProvider("plain", ops = setOf(Ops.EVM_BROADCAST, Ops.BTC_BROADCAST), networks = setOf(ETH, BTC))
        val protectedRpc = FakeProvider(
            "protected",
            ops = setOf(Ops.EVM_BROADCAST, Ops.BTC_BROADCAST),
            networks = setOf(ETH, BTC),
            traits = setOf(Trait.KEYLESS, Trait.MEV_PROTECTED),
        )
        val configs = mapOf("plain" to ProviderConfig(priority = 0), "protected" to ProviderConfig(priority = 1))

        assertEquals(listOf("protected"), route(listOf(plain, protectedRpc), op = Ops.EVM_BROADCAST, configs = configs))
        assertEquals(listOf("plain", "protected"), route(listOf(plain, protectedRpc), net = BTC, op = Ops.BTC_BROADCAST, configs = configs))
    }

    @Test
    fun aBroadcastOnEvmOffersOtherProvidersOnlyWhileNoMevProviderIsHealthy() {
        val plain = FakeProvider("plain", ops = setOf(Ops.EVM_BROADCAST))
        val protectedRpc = FakeProvider("protected", ops = setOf(Ops.EVM_BROADCAST), traits = setOf(Trait.KEYLESS, Trait.MEV_PROTECTED))

        assertEquals(listOf("plain"), route(listOf(plain, protectedRpc), op = Ops.EVM_BROADCAST, state = coolingState("protected")))
    }

    @Test
    fun aFallbackOnlyProviderIsOfferedOnlyWhenNoOtherProviderCanServe() {
        val others = FakeProvider("others", ops = setOf(Ops.EVM_NATIVE_BALANCE))
        val fallback = FakeProvider("fallback", ops = setOf(Ops.EVM_NATIVE_BALANCE), traits = setOf(Trait.KEYLESS, Trait.FALLBACK_ONLY))

        assertEquals(listOf("others"), route(listOf(fallback, others), op = Ops.EVM_NATIVE_BALANCE))
        assertEquals(listOf("fallback"), route(listOf(fallback, others), op = Ops.EVM_NATIVE_BALANCE, state = coolingState("others")))
    }

    @Test
    fun aFallbackOnlyProviderIsNotOfferedForABroadcastWhileAnMevProviderIsHealthy() {
        val protectedRpc = FakeProvider("protected", ops = setOf(Ops.EVM_BROADCAST), traits = setOf(Trait.KEYLESS, Trait.MEV_PROTECTED))
        val fallback = FakeProvider("fallback", ops = setOf(Ops.EVM_BROADCAST), traits = setOf(Trait.KEYLESS, Trait.FALLBACK_ONLY))

        assertEquals(listOf("protected"), route(listOf(fallback, protectedRpc), op = Ops.EVM_BROADCAST))
        assertEquals(listOf("fallback"), route(listOf(fallback, protectedRpc), op = Ops.EVM_BROADCAST, state = coolingState("protected")))
    }

    @Test
    fun balanceReadsOfferDataProvidersBeforeRpcProviders() {
        val rpc = FakeProvider("rpc", kind = Kind.RPC, ops = setOf(Ops.EVM_NATIVE_BALANCE))
        val data = FakeProvider("data", kind = Kind.DATA, ops = setOf(Ops.EVM_NATIVE_BALANCE))
        val configs = mapOf("rpc" to ProviderConfig(priority = 0), "data" to ProviderConfig(priority = 1))

        assertEquals(listOf("data", "rpc"), route(listOf(rpc, data), op = Ops.EVM_NATIVE_BALANCE, configs = configs))
    }

    @Test
    fun providersThatTieShareCallsByWeight() {
        val router = Router(listOf(FakeProvider("a"), FakeProvider("b")))
        val configs = mapOf("a" to ProviderConfig(weight = 2), "b" to ProviderConfig(weight = 1))

        val firsts = List(3) { router.route(ETH, Ops.BTC_TIP_HEIGHT, configs, everyone, 0L, emptySet(), emptySet()).first().id }

        assertEquals(listOf("a", "b", "a"), firsts)
    }
}
