package app.lumio.data.route

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.data.ETH
import app.lumio.data.FakeProvider
import app.lumio.data.config.ProviderConfigStore
import app.lumio.data.config.withApiKey
import app.lumio.data.config.withEnabled
import app.lumio.data.config.withMoved
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.Trait
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The settings the screen saves, read back from the vault as the router reads them: a switch, an order and a key decide who serves a call. */
class ProviderSettingsRoutingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val session by lazy {
        VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
    }

    private val healthy = object : ProviderState {
        override fun available(providerId: String, nowMs: Long): Boolean = true

        override fun score(providerId: String, nowMs: Long): Double = 0.0
    }

    /** The providers the router offers for a balance read, under the settings saved in [session]. */
    private fun offeredBy(vararg providers: FakeProvider): List<String> =
        Router(providers.toList())
            .route(ETH, OpKey.on(ETH, "nativeBalance"), ProviderConfigStore(session).load(), healthy, nowMs = 0L, tried = emptySet(), excluded = emptySet())
            .map { it.id }

    @Test
    fun aKeyRequiredProviderIsSkippedWithoutAKeyAndOfferedWithOne() {
        val etherscan = FakeProvider("etherscan", traits = setOf(Trait.KEY_REQUIRED))
        val other = FakeProvider("other")

        assertEquals(listOf("other"), offeredBy(etherscan, other))

        ProviderConfigStore(session).save(withApiKey(emptyMap(), "etherscan", "k-42"))

        assertEquals(setOf("etherscan", "other"), offeredBy(etherscan, other).toSet())
    }

    @Test
    fun aDisabledProviderIsNotOffered() {
        ProviderConfigStore(session).save(withEnabled(emptyMap(), "a", on = false))

        assertEquals(listOf("b"), offeredBy(FakeProvider("a"), FakeProvider("b")))
    }

    @Test
    fun theSavedOrderDecidesWhichProviderIsOfferedFirst() {
        ProviderConfigStore(session).save(withMoved(emptyMap(), listOf("a", "b"), "b", up = true))

        assertEquals("b", offeredBy(FakeProvider("a"), FakeProvider("b")).first())
    }
}
