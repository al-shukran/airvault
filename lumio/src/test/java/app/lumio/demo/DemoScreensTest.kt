package app.lumio.demo

import app.airvault.core.model.AccountId
import app.lumio.data.net.Networks
import app.lumio.network.NetworkMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The table of mockup frames that debug builds open directly: every id resolves to a route, and the capture script's list names the same ids. */
class DemoScreensTest {
    private val address = "0x" + "ab".repeat(20)

    private fun context(mode: NetworkMode) = DemoContext(
        walletId = "w1",
        mode = mode,
        evm = List(3) { AccountId.of(Networks.evm(1), address) },
        bitcoin = AccountId.of(Networks.BTC_MAINNET, "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"),
        sent = "acct|tx1",
        receivedToken = "acct|tx2",
    )

    @Test
    fun everyIdResolvesToARoute() {
        for (screen in DemoScreens.all) {
            val route = screen.route(context(screen.mode))
            assertTrue("${screen.id} has no route", route.isNotBlank())
            assertFalse("${screen.id} left a placeholder in $route", route.contains('{'))
            assertEquals(screen, DemoScreens.byId(screen.id))
        }
    }

    @Test
    fun idsAreUniqueAndNamedByPartAndNumber() {
        val ids = DemoScreens.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { Regex("p[12]-\\d\\d").matches(it) })
    }

    @Test
    fun theCaptureListNamesTheSameFramesAndThemes() {
        val file = listOf("../tools/mockup-capture/ids.tsv", "tools/mockup-capture/ids.tsv").map(::File).first { it.isFile }
        val listed = file.readLines().filter { it.isNotBlank() }.associate { line ->
            val parts = line.split('\t')
            parts[0] to parts[1].split(',').toSet()
        }
        val table = DemoScreens.all.associate { screen -> screen.id to screen.themes.map { it.name.lowercase() }.toSet() }
        assertEquals(listed, table)
    }

    @Test
    fun anUnknownIdHasNoScreen() {
        assertEquals(null, DemoScreens.byId("p9-99"))
        assertEquals(null, DemoScreens.byId(null))
    }
}
