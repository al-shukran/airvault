package app.lumio.evm

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The EVM settings as a vault record, and the changes that check their input before they are stored. */
class EvmSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()
    private val session by lazy {
        VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it * 7 + 3).toByte() })
    }
    private val server = MockWebServer()
    private val rpc = EvmRpc(allowLocalHttp = true, timeoutMs = 2_000L)

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private val url: String get() = server.url("/").toString()

    private fun answer(result: String) = MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"result":"$result"}""").build()

    @Test
    fun theDefaultsShowMainnetAndSepolia() {
        assertEquals(setOf(1L, 10143L, 11155111L), EvmSettings().enabled)
        assertEquals(5, EvmSettings().addressCount)
    }

    @Test
    fun aMissingRecordReadsAsTheDefaults() {
        assertEquals(EvmSettings(), EvmSettingsStore.read(session))
    }

    @Test
    fun aDamagedRecordReadsAsTheDefaults() {
        session.putVaultRecord(EvmSettings.RECORD, "not json".encodeToByteArray())

        assertEquals(EvmSettings(), EvmSettingsStore.read(session))
    }

    @Test
    fun settingsSurviveAWriteAndARead() {
        val settings = EvmSettings(
            addressCount = 3,
            enabled = setOf(1L, 999L),
            rpc = mapOf(1L to "https://rpc.example.org"),
            custom = listOf(CustomChain(999L, "Example", "EXM", testnet = true)),
        )

        EvmSettingsStore.write(session, settings)

        assertEquals(settings, EvmSettingsStore.read(session))
    }

    @Test
    fun aMonadTestnetTurnedOffAtTheCurrentVersionStaysOff() {
        EvmSettingsStore.write(session, EvmEdits.setEnabled(EvmSettings(), 10143L, false))

        assertFalse(10143L in EvmSettingsStore.read(session).enabled)
        assertTrue(1L in EvmSettingsStore.read(session).enabled)
    }

    @Test
    fun theAddressCountIsBetweenOneAndTheMaximum() {
        assertEquals(20, EvmEdits.setAddressCount(EvmSettings(), 20).addressCount)
        assertThrows(IllegalArgumentException::class.java) { EvmEdits.setAddressCount(EvmSettings(), 0) }
        assertThrows(IllegalArgumentException::class.java) { EvmEdits.setAddressCount(EvmSettings(), 21) }
    }

    @Test
    fun aCustomChainWithABuiltInIdIsRefusedBeforeAnyRequest() {
        val input = CustomChainInput(chainId = "1", name = "Copy", symbol = "ETH", rpc = "https://rpc.example.org", testnet = false)

        assertThrows(IllegalArgumentException::class.java) { EvmEdits.addCustom(EvmSettings(), setOf(1L), input, rpc) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aCustomChainNeedsAWholeChainIdNameAndSymbol() {
        val base = CustomChainInput(chainId = "999", name = "Example", symbol = "EXM", rpc = "https://rpc.example.org", testnet = false)

        assertThrows(IllegalArgumentException::class.java) { EvmEdits.addCustom(EvmSettings(), emptySet(), base.copy(chainId = "0"), rpc) }
        assertThrows(IllegalArgumentException::class.java) { EvmEdits.addCustom(EvmSettings(), emptySet(), base.copy(chainId = "x1"), rpc) }
        assertThrows(IllegalArgumentException::class.java) { EvmEdits.addCustom(EvmSettings(), emptySet(), base.copy(name = " "), rpc) }
        assertThrows(IllegalArgumentException::class.java) { EvmEdits.addCustom(EvmSettings(), emptySet(), base.copy(symbol = "E-X"), rpc) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aCustomChainWhoseEndpointServesAnotherChainIsRefused() {
        server.enqueue(answer("0x1"))
        val input = CustomChainInput(chainId = "999", name = "Example", symbol = "EXM", rpc = url, testnet = false)

        val failure = assertThrows(RpcFailure::class.java) { EvmEdits.addCustom(EvmSettings(), emptySet(), input, rpc) }
        assertEquals(RpcFailure.Reason.WRONG_CHAIN, failure.reason)
    }

    @Test
    fun aCustomChainWithAMatchingEndpointIsEnabledWithItsRpc() {
        server.enqueue(answer("0x3e7"))
        val input = CustomChainInput(chainId = "999", name = "Example", symbol = "EXM", rpc = url, testnet = true)

        val updated = EvmEdits.addCustom(EvmSettings(), emptySet(), input, rpc)

        assertEquals(listOf(CustomChain(999L, "Example", "EXM", testnet = true)), updated.custom)
        assertTrue(999L in updated.enabled)
        assertEquals(url, updated.rpc[999L])
    }

    @Test
    fun removingACustomChainDropsItsEnabledFlagAndItsRpc() {
        val settings = EvmSettings(
            enabled = setOf(999L),
            rpc = mapOf(999L to "https://rpc.example.org"),
            custom = listOf(CustomChain(999L, "Example", "EXM", testnet = true)),
        )

        val updated = EvmEdits.removeCustom(settings, 999L)

        assertEquals(emptyList<CustomChain>(), updated.custom)
        assertFalse(999L in updated.enabled)
        assertFalse(updated.rpc.containsKey(999L))
    }

    @Test
    fun resettingAnRpcKeepsTheChainAndItsFlag() {
        val settings = EvmSettings(enabled = setOf(1L), rpc = mapOf(1L to "https://rpc.example.org"))

        val updated = EvmEdits.resetRpc(settings, 1L)

        assertTrue(updated.rpc.isEmpty())
        assertEquals(setOf(1L), updated.enabled)
    }

    private val oldRpc = "https://old.example.org"

    @Test
    fun anRpcThatServesTheChainIsSavedAfterTheCheck() {
        EvmSettingsStore.write(session, EvmSettings(rpc = mapOf(1L to oldRpc)))
        server.enqueue(answer("0x1"))

        val refusal = EvmSettingsStore.change(session, rpc) { s, r -> EvmEdits.setRpc(s, 1L, url, r) }

        assertNull(refusal)
        assertEquals(url, EvmSettingsStore.read(session).rpc[1L])
    }

    @Test
    fun anRpcThatServesAnotherChainIsRefusedAndTheOldAddressStays() {
        EvmSettingsStore.write(session, EvmSettings(rpc = mapOf(1L to oldRpc)))
        server.enqueue(answer("0x2"))

        val refusal = EvmSettingsStore.change(session, rpc) { s, r -> EvmEdits.setRpc(s, 1L, url, r) }

        assertEquals("The endpoint reports chain 2, not 1.", refusal)
        assertEquals(oldRpc, EvmSettingsStore.read(session).rpc[1L])
    }

    @Test
    fun anAddressThatIsNotHttpsIsRefusedBeforeAnyRequestAndTheOldAddressStays() {
        EvmSettingsStore.write(session, EvmSettings(rpc = mapOf(1L to oldRpc)))
        val releaseRpc = EvmRpc(allowLocalHttp = false, timeoutMs = 2_000L)

        val refusal = EvmSettingsStore.change(session, releaseRpc) { s, r -> EvmEdits.setRpc(s, 1L, url, r) }

        assertNotNull(refusal)
        assertEquals(0, server.requestCount)
        assertEquals(oldRpc, EvmSettingsStore.read(session).rpc[1L])
    }
}
