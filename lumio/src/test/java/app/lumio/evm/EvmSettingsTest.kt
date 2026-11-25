package app.lumio.evm

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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
        // The value of lumio-v1.0.0. A record stores every field, but a vault of that version stores none of an untouched one and reads it as this.
        assertEquals(setOf(1L, 10143L, 11155111L), EvmSettings.DEFAULT_ENABLED)
        assertEquals(setOf(1L, 10143L, 11155111L), EvmSettings().enabled)
        assertEquals(5, EvmSettings().addressCount)
    }

    @Test
    fun aMissingRecordReadsAsTheDefaults() {
        assertEquals(EvmSettings(), EvmSettingsStore.read(session))
    }

    private val unreadable = """{"addressCount":8,"enabled":[1,999],"rpc":{"1":123},"custom":[{"chainId":999,"name":"Test","symbol":"TST","testnet":true}]}"""

    @Test
    fun aChangeIsRefusedAndTheStoredRecordKeptWhenTheRecordDoesNotParse() {
        session.putVaultRecord(EvmSettings.RECORD, unreadable.encodeToByteArray())

        val refusal = EvmSettingsStore.change(session, rpc) { s, _ -> EvmEdits.setEnabled(s, 1L, false) }

        assertNotNull(refusal)
        assertEquals(unreadable, session.getVaultRecord(EvmSettings.RECORD)!!.decodeToString())
        assertEquals(EvmSettings(), EvmSettingsStore.read(session))
    }

    @Test
    fun aCustomNetworkIsRefusedAndTheStoredRecordKeptWhenTheRecordDoesNotParse() {
        session.putVaultRecord(EvmSettings.RECORD, unreadable.encodeToByteArray())
        server.enqueue(answer("0x1"))

        val refusal = EvmSettingsStore.addCustom(session, rpc, setOf(1L), CustomChainInput("1", "Test", "TST", url, testnet = true))

        assertNotNull(refusal)
        assertEquals(unreadable, session.getVaultRecord(EvmSettings.RECORD)!!.decodeToString())
    }

    @Test
    fun everyFieldIsStoredSoALaterDefaultDoesNotChangeTheRecord() {
        EvmSettingsStore.write(session, EvmSettings())

        val record = session.getVaultRecord(EvmSettings.RECORD)!!.decodeToString()
        assertTrue(record, """"addressCount":5""" in record)
        assertTrue(record, """"enabled":[""" in record)
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
            rpc = mapOf(1L to rpcInUse("https://rpc.example.org")),
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

        assertThrows(IllegalArgumentException::class.java) { EvmEdits.addCustom(EvmSettings(), setOf(1L), input, rpc, nowMs) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aCustomChainNeedsAWholeChainIdNameAndSymbol() {
        val base = CustomChainInput(chainId = "999", name = "Example", symbol = "EXM", rpc = "https://rpc.example.org", testnet = false)

        assertThrows(IllegalArgumentException::class.java) { EvmEdits.addCustom(EvmSettings(), emptySet(), base.copy(chainId = "0"), rpc, nowMs) }
        assertThrows(IllegalArgumentException::class.java) { EvmEdits.addCustom(EvmSettings(), emptySet(), base.copy(chainId = "x1"), rpc, nowMs) }
        assertThrows(IllegalArgumentException::class.java) { EvmEdits.addCustom(EvmSettings(), emptySet(), base.copy(name = " "), rpc, nowMs) }
        assertThrows(IllegalArgumentException::class.java) { EvmEdits.addCustom(EvmSettings(), emptySet(), base.copy(symbol = "E-X"), rpc, nowMs) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aCustomChainWhoseEndpointServesAnotherChainIsRefused() {
        server.enqueue(answer("0x1"))
        val input = CustomChainInput(chainId = "999", name = "Example", symbol = "EXM", rpc = url, testnet = false)

        val failure = assertThrows(RpcFailure::class.java) { EvmEdits.addCustom(EvmSettings(), emptySet(), input, rpc, nowMs) }
        assertEquals(RpcFailure.Reason.WRONG_CHAIN, failure.reason)
    }

    @Test
    fun aCustomChainWithAMatchingEndpointIsEnabledWithItsRpc() {
        server.enqueue(answer("0x3e7"))
        val input = CustomChainInput(chainId = "999", name = "Example", symbol = "EXM", rpc = url, testnet = true)

        val updated = EvmEdits.addCustom(EvmSettings(), emptySet(), input, rpc, nowMs)

        assertEquals(listOf(CustomChain(999L, "Example", "EXM", testnet = true)), updated.custom)
        assertTrue(999L in updated.enabled)
        assertEquals(url, updated.activeRpc(999L))
        assertEquals(nowMs, updated.rpc[999L]?.activeSince)
    }

    @Test
    fun removingACustomChainDropsItsEnabledFlagAndItsRpc() {
        val settings = EvmSettings(
            enabled = setOf(999L),
            rpc = mapOf(999L to rpcInUse("https://rpc.example.org")),
            custom = listOf(CustomChain(999L, "Example", "EXM", testnet = true)),
        )

        val updated = EvmEdits.removeCustom(settings, 999L)

        assertEquals(emptyList<CustomChain>(), updated.custom)
        assertFalse(999L in updated.enabled)
        assertFalse(updated.rpc.containsKey(999L))
    }

    @Test
    fun resettingAnRpcKeepsTheChainItsFlagAndItsCustomAddresses() {
        val settings = EvmSettings(enabled = setOf(1L), rpc = mapOf(1L to rpcInUse("https://rpc.example.org")))

        val updated = EvmEdits.resetRpc(settings, 1L)

        assertNull(updated.activeRpc(1L))
        assertEquals(listOf("https://rpc.example.org"), updated.rpc[1L]?.custom)
        assertEquals(setOf(1L), updated.enabled)
    }

    @Test
    fun aChainKeepsEveryRpcTheOwnerAddsAndTheLatestIsInUse() {
        server.enqueue(answer("0x1"))
        server.enqueue(answer("0x1"))
        val first = server.url("/first").toString()
        val second = server.url("/second").toString()

        val updated = EvmEdits.setRpc(EvmEdits.setRpc(EvmSettings(), 1L, first, rpc, nowMs), 1L, second, rpc, nowMs)

        assertEquals(ChainRpcs(custom = listOf(first, second), active = second, activeSince = nowMs), updated.rpc[1L])
    }

    @Test
    fun anAddressAlreadyListedIsSelectedAndNotListedTwice() {
        server.enqueue(answer("0x1"))
        val first = server.url("/first").toString()
        val second = server.url("/second").toString()
        val settings = EvmSettings(rpc = mapOf(1L to ChainRpcs(custom = listOf(first, second), active = second)))

        val updated = EvmEdits.setRpc(settings, 1L, first, rpc, nowMs)

        assertEquals(ChainRpcs(custom = listOf(first, second), active = first, activeSince = nowMs), updated.rpc[1L])
    }

    @Test
    fun anAddressTheChainDoesNotListCannotBeSelected() {
        assertThrows(IllegalArgumentException::class.java) { EvmEdits.useRpc(EvmSettings(), 1L, "https://rpc.example.org", nowMs) }
    }

    @Test
    fun aChainsRpcsCanBeSelectedAndRemovedWithoutTouchingAnotherChain() {
        val first = "https://first.example.org"
        val second = "https://second.example.org"
        val other = "https://other.example.org"
        val settings = EvmSettings(
            rpc = mapOf(
                1L to ChainRpcs(custom = listOf(first, second), active = second),
                10143L to ChainRpcs(custom = listOf(other), active = other),
            ),
        )

        val selected = EvmEdits.useRpc(settings, 1L, first, nowMs)
        assertEquals(ChainRpcs(custom = listOf(first, second), active = first, activeSince = nowMs), selected.rpc[1L])
        assertEquals(settings.rpc[10143L], selected.rpc[10143L])

        val removedInUse = EvmEdits.removeRpc(selected, 1L, first)
        assertEquals(ChainRpcs(custom = listOf(second), active = null), removedInUse.rpc[1L])

        val removedLast = EvmEdits.removeRpc(removedInUse, 1L, second)
        assertFalse(removedLast.rpc.containsKey(1L))
        assertEquals(settings.rpc[10143L], removedLast.rpc[10143L])
    }

    @Test
    fun anAddressInUseKeepsTheTimeItWasSetWhileItStaysInUse() {
        val first = "https://first.example.org"
        val second = "https://second.example.org"
        val settings = EvmSettings(rpc = mapOf(1L to ChainRpcs(custom = listOf(first, second), active = first, activeSince = nowMs)))

        val again = EvmEdits.useRpc(settings, 1L, first, nowMs + 5_000L)
        assertEquals(nowMs, again.rpc[1L]?.activeSince)

        val switched = EvmEdits.useRpc(again, 1L, second, nowMs + 5_000L)
        assertEquals(nowMs + 5_000L, switched.rpc[1L]?.activeSince)
    }

    @Test
    fun resettingOrRemovingTheAddressInUseClearsItsTime() {
        val first = "https://first.example.org"
        val second = "https://second.example.org"
        val settings = EvmSettings(rpc = mapOf(1L to ChainRpcs(custom = listOf(first, second), active = first, activeSince = nowMs)))

        assertEquals(ChainRpcs(custom = listOf(first, second)), EvmEdits.resetRpc(settings, 1L).rpc[1L])
        assertEquals(ChainRpcs(custom = listOf(second)), EvmEdits.removeRpc(settings, 1L, first).rpc[1L])
        assertEquals(nowMs, EvmEdits.removeRpc(settings, 1L, second).rpc[1L]?.activeSince)
    }

    @Test
    fun theRpcsOfEveryChainSurviveAWriteAndARead() {
        val settings = EvmSettings(
            rpc = mapOf(
                1L to ChainRpcs(custom = listOf("https://first.example.org", "https://second.example.org"), active = "https://second.example.org"),
                10143L to ChainRpcs(custom = listOf("https://other.example.org"), active = null),
            ),
        )

        EvmSettingsStore.write(session, settings)

        assertEquals(settings, EvmSettingsStore.read(session))
    }

    @Test
    fun onlyTheRpcsInUseAreReadFrom() {
        val settings = EvmSettings(
            rpc = mapOf(
                1L to ChainRpcs(custom = listOf("https://first.example.org"), active = "https://first.example.org"),
                10143L to ChainRpcs(custom = listOf("https://other.example.org"), active = null),
            ),
        )

        assertEquals(mapOf("1" to "https://first.example.org"), settings.activeRpcUrls())
    }

    private val oldRpc = "https://old.example.org"

    /** 2026-11-19 12:00 UTC, the time an address goes into use in these tests. */
    private val nowMs = 1_795_089_650_000L

    @Test
    fun anRpcThatServesTheChainIsSavedAfterTheCheck() {
        EvmSettingsStore.write(session, EvmSettings(rpc = mapOf(1L to rpcInUse(oldRpc))))
        server.enqueue(answer("0x1"))

        val refusal = EvmSettingsStore.change(session, rpc) { s, r -> EvmEdits.setRpc(s, 1L, url, r, nowMs) }

        assertNull(refusal)
        assertEquals(url, EvmSettingsStore.read(session).activeRpc(1L))
    }

    @Test
    fun anRpcThatServesAnotherChainIsRefusedAndTheOldAddressStays() {
        EvmSettingsStore.write(session, EvmSettings(rpc = mapOf(1L to rpcInUse(oldRpc))))
        server.enqueue(answer("0x2"))

        val refusal = EvmSettingsStore.change(session, rpc) { s, r -> EvmEdits.setRpc(s, 1L, url, r, nowMs) }

        assertEquals("The endpoint reports chain 2, not 1.", refusal)
        assertEquals(oldRpc, EvmSettingsStore.read(session).activeRpc(1L))
    }

    @Test
    fun anAddressThatIsNotHttpsIsRefusedBeforeAnyRequestAndTheOldAddressStays() {
        EvmSettingsStore.write(session, EvmSettings(rpc = mapOf(1L to rpcInUse(oldRpc))))
        val releaseRpc = EvmRpc(allowLocalHttp = false, timeoutMs = 2_000L)

        val refusal = EvmSettingsStore.change(session, releaseRpc) { s, r -> EvmEdits.setRpc(s, 1L, url, r, nowMs) }

        assertNotNull(refusal)
        assertEquals(0, server.requestCount)
        assertEquals(oldRpc, EvmSettingsStore.read(session).activeRpc(1L))
    }

    private val newNetwork = CustomChainInput(chainId = "999", name = "Example", symbol = "EXM", rpc = "https://rpc.example.org", testnet = false)

    @Test
    fun aNewNetworkWithAnExplorerKeepsIt() {
        server.enqueue(answer("0x3e7"))
        val input = newNetwork.copy(rpc = url, explorer = "https://explorer.example.org")

        val updated = EvmEdits.addCustom(EvmSettings(), emptySet(), input, rpc, nowMs)

        assertEquals(CustomChain(999L, "Example", "EXM", testnet = false, explorer = "https://explorer.example.org/"), updated.custom.single())
    }

    @Test
    fun anExplorerIsAnHttpsAddressOrNone() {
        val refusal = assertThrows(EvmRefusal::class.java) {
            EvmEdits.addCustom(EvmSettings(), emptySet(), newNetwork.copy(explorer = "http://explorer.example.org"), rpc, nowMs)
        }

        assertEquals(CustomNetworkField.EXPLORER, refusal.field)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRefusalNamesTheFieldItConcerns() {
        fun fieldOf(input: CustomChainInput, builtInIds: Set<Long> = emptySet()): CustomNetworkField? =
            assertThrows(EvmRefusal::class.java) { EvmEdits.addCustom(EvmSettings(), builtInIds, input, rpc, nowMs) }.field

        assertEquals(CustomNetworkField.CHAIN_ID, fieldOf(newNetwork.copy(chainId = "0")))
        assertEquals(CustomNetworkField.CHAIN_ID, fieldOf(newNetwork.copy(chainId = "1"), builtInIds = setOf(1L)))
        assertEquals(CustomNetworkField.NAME, fieldOf(newNetwork.copy(name = " ")))
        assertEquals(CustomNetworkField.SYMBOL, fieldOf(newNetwork.copy(symbol = "E-X")))
        assertEquals(CustomNetworkField.RPC, fieldOf(newNetwork.copy(rpc = "ftp://rpc.example.org")))
        assertEquals(CustomNetworkField.EXPLORER, fieldOf(newNetwork.copy(explorer = "not a web address")))
    }

    @Test
    fun aChainIdAlreadyAddedIsRefusedBeforeAnyRequest() {
        val current = EvmSettings(custom = listOf(CustomChain(999L, "Example", "EXM", testnet = false)))

        val refusal = assertThrows(EvmRefusal::class.java) { EvmEdits.addCustom(current, emptySet(), newNetwork, rpc, nowMs) }

        assertEquals(CustomNetworkField.CHAIN_ID, refusal.field)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aNewNetworkIsStoredOnlyWhenTheEndpointAnswersWithItsChainId() {
        server.enqueue(answer("0x1"))
        val input = newNetwork.copy(rpc = url)

        val refused = EvmSettingsStore.addCustom(session, rpc, emptySet(), input)

        assertEquals(CustomNetworkField.RPC, refused?.field)
        assertEquals(emptyList<CustomChain>(), EvmSettingsStore.read(session).custom)

        server.enqueue(answer("0x3e7"))
        assertNull(EvmSettingsStore.addCustom(session, rpc, emptySet(), input))
        assertEquals(listOf(CustomChain(999L, "Example", "EXM", testnet = false)), EvmSettingsStore.read(session).custom)
        assertEquals(url, EvmSettingsStore.read(session).activeRpc(999L))
    }

    @Test
    fun aChangeSavedWhileANetworkIsBeingCheckedIsNotOverwritten() {
        // The Add Network check holds its endpoint until the test lets it answer. A toggle on another page runs meanwhile.
        val checking = CountDownLatch(1)
        val answerNow = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                checking.countDown()
                answerNow.await(5, TimeUnit.SECONDS)
                return answer("0x3e7")
            }
        }
        var refusal: EvmRefusal? = null
        val add = thread { refusal = EvmSettingsStore.addCustom(session, rpc, emptySet(), newNetwork.copy(rpc = url)) }
        checking.await(5, TimeUnit.SECONDS)

        val toggle = thread { EvmSettingsStore.change(session, rpc) { s, _ -> EvmEdits.setEnabled(s, 10143L, false) } }
        toggle.join(1_000)
        answerNow.countDown()
        add.join()
        toggle.join()

        assertNull(refusal)
        val stored = EvmSettingsStore.read(session)
        assertEquals(listOf(999L), stored.custom.map { it.chainId })
        assertFalse(10143L in stored.enabled)
    }
}
