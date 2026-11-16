package app.lumio.btc

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lumio.pair.PairFixtures
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Mainnet and testnet4 stay apart: their sections, their totals, their owner's servers and their explorer links. */
class PortfolioTest {
    private val seed = PairFixtures.seed
    private val mainnet = PairFixtures.account(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)
    private val testnet = PairFixtures.account(seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
    private val mainnetRef = BtcAccountRef("wallet-1", mainnet)
    private val testnetRef = BtcAccountRef("wallet-1", testnet)

    @Test
    fun mainnetAndTestnetAreTwoSectionsAndNeverSummed() {
        val rows = listOf(
            BtcRow(mainnetRef, snapshot(mainnetRef, confirmed = 100_000L, unconfirmed = 0L)),
            BtcRow(testnetRef, snapshot(testnetRef, confirmed = 5_000_000L, unconfirmed = -700L)),
        )

        val sections = BtcPortfolio.sections(rows)

        assertEquals(listOf(BtcNetwork.MAINNET, BtcNetwork.TESTNET4), sections.map { it.network })
        assertEquals(100_000L, sections[0].confirmedSats, "mainnet total holds only the mainnet account")
        assertEquals(0L, sections[0].unconfirmedSats)
        assertEquals(5_000_000L, sections[1].confirmedSats, "testnet4 total holds only the testnet4 account")
        assertEquals(-700L, sections[1].unconfirmedSats)
    }

    @Test
    fun aNetworkWithNoAccountHasNoSection() {
        val sections = BtcPortfolio.sections(listOf(BtcRow(testnetRef, null)))

        assertEquals(listOf(BtcNetwork.TESTNET4), sections.map { it.network })
    }

    @Test
    fun anUnscannedAccountAddsNothingToItsSectionTotal() {
        val sections = BtcPortfolio.sections(
            listOf(
                BtcRow(testnetRef, snapshot(testnetRef, confirmed = 2_000L, unconfirmed = 0L)),
                BtcRow(BtcAccountRef("wallet-1", PairFixtures.account(seed, BtcNetwork.TESTNET4, ScriptType.TAPROOT)), null),
            ),
        )

        assertEquals(2_000L, sections.single().confirmedSats)
    }

    @Test
    fun aLastKnownBalanceCountsOnlyForTheSameAccount() {
        val cached = snapshot(testnetRef, confirmed = 9L, unconfirmed = 0L)
        val lookup = { key: String -> if (key == testnetRef.key) cached else null }

        assertEquals(cached, BtcPortfolio.rows(listOf(testnetRef), lookup).single().snapshot)

        val otherAccount = BtcAccountRef("wallet-1", PairFixtures.account(seed, BtcNetwork.TESTNET4, ScriptType.LEGACY))
        assertNull(BtcPortfolio.rows(listOf(otherAccount), lookup).single().snapshot)
    }

    @Test
    fun aCachedScanOfAnotherIdentityIsNotShown() {
        val otherIdentity = snapshot(testnetRef, confirmed = 1L, unconfirmed = 0L).copy(identity = "tb1qsomeoneelse")
        val rows = BtcPortfolio.rows(listOf(testnetRef)) { otherIdentity }

        assertNull(rows.single().snapshot, "a cache whose first receive address differs belongs to another account")
    }

    @Test
    fun aScanOlderThanTheTimeToLiveIsStale() {
        assertFalse(isStale(scannedAtMs = 1_000L, nowMs = 60_999L, ttlMs = 60_000L))
        assertTrue(isStale(scannedAtMs = 1_000L, nowMs = 61_001L, ttlMs = 60_000L))
    }

    @Test
    fun eachNetworkHasItsOwnDefaultServer() {
        assertEquals("https://mempool.space/api", EsploraServers.defaultFor(BtcNetwork.MAINNET))
        assertEquals("https://mempool.space/testnet4/api", EsploraServers.defaultFor(BtcNetwork.TESTNET4))
    }

    @Test
    fun anOverrideOfOneNetworkNeverChangesTheOther() {
        val overrides = mapOf(BtcNetwork.MAINNET to "https://node.example.org/api")

        assertEquals("https://node.example.org/api", EsploraServers.baseUrl(BtcNetwork.MAINNET, overrides))
        assertEquals(EsploraServers.TESTNET4_DEFAULT, EsploraServers.baseUrl(BtcNetwork.TESTNET4, overrides))
    }

    @Test
    fun aServerMustBeHttpsAndAPlainBaseUrl() {
        assertEquals("https://node.example.org/api", EsploraServers.normalize("  https://node.example.org/api/  "))
        assertFailsWith<IllegalArgumentException> { EsploraServers.normalize("http://node.example.org/api") }
        assertFailsWith<IllegalArgumentException> { EsploraServers.normalize("node.example.org/api") }
        assertFailsWith<IllegalArgumentException> { EsploraServers.normalize("https://node.example.org/api?x=1") }
        assertFailsWith<IllegalArgumentException> { EsploraServers.normalize("https://user@node.example.org/api") }
        assertFailsWith<IllegalArgumentException> { EsploraServers.normalize("https:///api") }
    }

    @Test
    fun theExplorerLinkIsOnTheNetworksOwnPath() {
        assertEquals("https://mempool.space/tx/abc", BtcPortfolio.explorerTxUrl(BtcNetwork.MAINNET, "abc"))
        assertEquals("https://mempool.space/testnet4/tx/abc", BtcPortfolio.explorerTxUrl(BtcNetwork.TESTNET4, "abc"))
        assertFalse(BtcPortfolio.explorerTxUrl(BtcNetwork.MAINNET, "abc").contains("testnet4"))
    }

    private fun snapshot(ref: BtcAccountRef, confirmed: Long, unconfirmed: Long) = BtcSnapshot(
        key = ref.key,
        identity = ref.account.receiveAddress(0),
        scannedAtMs = 1_000L,
        tipHeight = 850_000L,
        confirmedSats = confirmed,
        unconfirmedSats = unconfirmed,
        nextReceiveIndex = 0,
        nextReceiveAddress = ref.account.receiveAddress(0),
        utxos = emptyList(),
        transactions = emptyList(),
    )
}
