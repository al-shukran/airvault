package app.lumio.ui.settings

import app.lensly.core.btc.BtcNetwork
import app.lumio.btc.EsploraServers
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/** What the settings do with an entry as the owner types it: a valid entry applies, an invalid one is refused, and nothing else changes. */
class SettingsEditsTest {

    @Test
    fun aValidMinimumIsAppliedWhenItDiffersAndNothingWhenItDoesNot() {
        assertEquals(MinimumEdit.Apply(2.5), minimumEdit("2.5", current = 1.0))
        assertEquals(MinimumEdit.Unchanged, minimumEdit("1.00", current = 1.0))
        assertEquals(MinimumEdit.Unchanged, minimumEdit(" 1 ", current = 1.0))
        assertEquals(MinimumEdit.Apply(0.0), minimumEdit("0", current = 1.0))
    }

    @Test
    fun aMinimumThatIsNotAnAmountIsRefusedWithTheReason() {
        for (text in listOf("", "-1", "abc", "1,5", "1000001", "1.234")) {
            val edit = minimumEdit(text, current = 1.0)
            assertIs<MinimumEdit.Refused>(edit, "text '$text'")
            assertEquals(MINIMUM_REFUSAL, edit.reason)
        }
    }

    @Test
    fun aServerTypedAsItsDefaultIsAppliedAsNoOverride() {
        val custom = "https://node.example.org/api"
        assertEquals(ServerEdit.Apply(null), serverEdit(BtcNetwork.MAINNET, EsploraServers.MAINNET_DEFAULT, current = custom))
    }

    @Test
    fun aValidServerIsAppliedAsItsBaseUrlWithNoTrailingSlash() {
        assertEquals(
            ServerEdit.Apply("https://node.example.org/testnet4"),
            serverEdit(BtcNetwork.TESTNET4, "  https://node.example.org/testnet4/  ", current = EsploraServers.TESTNET4_DEFAULT),
        )
    }

    @Test
    fun aServerThatNamesTheServerInUseChangesNothing() {
        assertEquals(ServerEdit.Unchanged, serverEdit(BtcNetwork.MAINNET, "https://node.example.org/api/", current = "https://node.example.org/api"))
        assertEquals(ServerEdit.Unchanged, serverEdit(BtcNetwork.MAINNET, EsploraServers.MAINNET_DEFAULT, current = EsploraServers.MAINNET_DEFAULT))
    }

    @Test
    fun aBlankServerMeansThePublicOneAndIsNoOverride() {
        val custom = "https://node.example.org/api"
        assertEquals(ServerEdit.Apply(null), serverEdit(BtcNetwork.MAINNET, "   ", current = custom))
        assertEquals(ServerEdit.Unchanged, serverEdit(BtcNetwork.MAINNET, "", current = EsploraServers.MAINNET_DEFAULT))
    }

    @Test
    fun anAddressThatIsNotHttpsIsRefusedAndNamesItsNetwork() {
        val mainnet = serverEdit(BtcNetwork.MAINNET, "http://plain.example.org", current = EsploraServers.MAINNET_DEFAULT)
        assertIs<ServerEdit.Refused>(mainnet)
        assertTrue(mainnet.reason.startsWith("Mainnet: "), mainnet.reason)

        val testnet = serverEdit(BtcNetwork.TESTNET4, "node.example.org/testnet4", current = EsploraServers.TESTNET4_DEFAULT)
        assertIs<ServerEdit.Refused>(testnet)
        assertTrue(testnet.reason.startsWith("Testnet4: "), testnet.reason)
    }

    @Test
    fun aSaveOfOneNetworkKeepsTheOtherNetworksOverride() {
        val both = mapOf(BtcNetwork.MAINNET to "https://a.example.org/api", BtcNetwork.TESTNET4 to "https://b.example.org/testnet4")

        assertEquals(
            mapOf(BtcNetwork.MAINNET to "https://c.example.org/api", BtcNetwork.TESTNET4 to "https://b.example.org/testnet4"),
            both.withServer(BtcNetwork.MAINNET, "https://c.example.org/api"),
        )
        assertEquals(mapOf(BtcNetwork.TESTNET4 to "https://b.example.org/testnet4"), both.withServer(BtcNetwork.MAINNET, null))
    }

    @Test
    fun aSequenceOfSavesEndsOnTheLastServerOfEachNetwork() {
        var overrides: Map<BtcNetwork, String> = emptyMap()
        overrides = overrides.withServer(BtcNetwork.MAINNET, "https://a.example.org/api")
        overrides = overrides.withServer(BtcNetwork.TESTNET4, "https://b.example.org/testnet4")
        overrides = overrides.withServer(BtcNetwork.MAINNET, "https://c.example.org/api")

        assertEquals(
            mapOf(BtcNetwork.MAINNET to "https://c.example.org/api", BtcNetwork.TESTNET4 to "https://b.example.org/testnet4"),
            overrides,
        )
    }
}
