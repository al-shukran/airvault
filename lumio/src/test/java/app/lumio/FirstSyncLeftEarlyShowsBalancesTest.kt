package app.lumio

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import android.os.Looper
import app.lensly.app.VaultHolder
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.ui.navigation.GateOutcome
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.evm.ChainRpcs
import app.lumio.evm.EvmSettings
import app.lumio.evm.EvmSettingsStore
import app.lumio.network.NetworkMode
import app.lumio.pair.FirstSync
import app.lumio.pair.PairFixtures
import app.lumio.data.db.LumioDatabase
import app.lumio.wallets.WalletsBoard
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The owner leaves the first sync early with View Accounts: the wallet page shows the paired wallet while the reads still run. The board must show the
 * balances the reads write once they end, and the first sync is still the only read of each Ethereum balance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LumioApp::class)
class FirstSyncLeftEarlyShowsBalancesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: LumioApp get() = context.applicationContext as LumioApp
    private val clock = FakeClock()
    private val vault by lazy { Vault(VaultLayout(File(tmp.root, "lumio-vault")), MemoryKeystore(), clock, KDF) }
    private val database by lazy { LumioDatabase.open(context, File(tmp.root, "lumio.db")) }
    private val holder by lazy { VaultHolder(vault, attach = { LumioVault(it, database) }) }
    private val draft = PairDraft()
    private lateinit var nav: NavHostController

    /** The owner's own RPC endpoint for the testnet chain, which answers every read locally and counts the balance reads. */
    private val rpc = MockWebServer()
    private val balanceReads = AtomicInteger(0)

    /** Holds every RPC answer back until the test opens it, so the sync is still running when the owner leaves it. */
    private val gate = CountDownLatch(1)

    @Before
    fun serveRpc() {
        rpc.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                gate.await()
                val body = request.body?.utf8().orEmpty()
                val result = when {
                    body.contains("eth_getBalance") -> {
                        balanceReads.incrementAndGet()
                        "0x1"
                    }
                    // No Multicall3 answer, so each balance is read on its own.
                    body.contains("eth_call") -> "0x"
                    else -> return MockResponse.Builder().code(400).build()
                }
                return MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"result":"$result"}""").build()
            }
        }
        rpc.start()
    }

    @After
    fun stopRpc() {
        rpc.close()
    }

    @Test
    fun leavingTheFirstSyncEarlyStillShowsTheBalancesItReads() {
        assertEquals(GateOutcome.Opened, runBlocking { createFirstVault(holder, PASSWORD.toCharArray(), context) })
        val rpcUrl = rpc.url("/").toString()
        EvmSettingsStore.write(
            checkNotNull(holder.openSession()),
            EvmSettings(addressCount = 1, enabled = setOf(MONAD_TESTNET), rpc = mapOf(MONAD_TESTNET to ChainRpcs(custom = listOf(rpcUrl), active = rpcUrl))),
        )
        setGraph()
        // The Wallets home is open on the testnet side with no wallet, as it is once the vault opens.
        val vaultAttachment = checkNotNull(holder.sessionAttachment() as? LumioVault)
        val board = WalletsBoard(app, vaultAttachment).also { vaultAttachment.wallets = it }
        board.showSide(NetworkMode.TESTNET, listVersion = 0)
        awaitUntil { board.view != null }

        compose.runOnUiThread {
            draft.pairing = PairFixtures.payloadOf(listOf(PairFixtures.ethereumAccount(0, "Account 0")))
            nav.navigate(LumioRoute.PAIR_REVIEW)
        }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Save Wallet").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save Wallet").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { currentRoute() == LumioRoute.FIRST_SYNC }
        val walletId = checkNotNull(nav.currentBackStackEntry?.arguments?.getString(LumioRoute.WALLET_ARG))
        compose.waitUntil(timeoutMillis = 10_000) { vaultAttachment.firstSyncs.get(walletId, NetworkMode.TESTNET) != null }
        val sync = checkNotNull(vaultAttachment.firstSyncs.get(walletId, NetworkMode.TESTNET)).sync

        // View Accounts: the wallet page shows the side for the new list while the reads are held back, so it reads the caches before they are written.
        board.showSide(NetworkMode.TESTNET, listVersion = 1)
        awaitUntil { board.view?.accountsOf(walletId)?.isNotEmpty() == true }
        assertNull(board.view?.accountsOf(walletId)?.single()?.balance, "The caches hold no balance while the sync is held back")

        gate.countDown()
        awaitUntil { FirstSync.isSettled(sync.rows.value) }
        // Visiting the wallet page again changes neither the side nor the list, so nothing but the first sync can show what it wrote.
        board.showSide(NetworkMode.TESTNET, listVersion = 1)
        val shown = eventually(timeoutMs = 10_000) { board.view?.accountsOf(walletId)?.single()?.balance != null }

        assertTrue(shown, "The wallet page shows the balance the first sync read")
        assertEquals(1, balanceReads.get(), "The Ethereum address is read once for the pairing")
    }

    private fun setGraph() {
        compose.setContent {
            ClearAirTheme {
                nav = rememberNavController()
                NavHost(navController = nav, startDestination = LumioRoute.WALLETS) {
                    composable(LumioRoute.WALLETS) { Text("Accounts root") }
                    composable(LumioRoute.PAIR_REVIEW) {
                        PairReviewRoute(navController = nav, holder = holder, draft = draft, onChanged = {}, onLock = {})
                    }
                    composable(
                        route = LumioRoute.FIRST_SYNC,
                        arguments = listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }),
                    ) { entry ->
                        FirstSyncRoute(
                            navController = nav,
                            holder = holder,
                            walletId = entry.arguments?.getString(LumioRoute.WALLET_ARG).orEmpty(),
                            mode = NetworkMode.TESTNET,
                            onLock = {},
                        )
                    }
                    composable(
                        route = LumioRoute.WALLET,
                        arguments = listOf(navArgument(LumioRoute.WALLET_ARG) { type = NavType.StringType }),
                    ) { Text("Wallet page") }
                }
            }
        }
    }

    private fun currentRoute(): String? = nav.currentBackStackEntry?.destination?.route

    /** Runs the main looper until [condition] holds, as the board's own tests do: its reads resume on the main thread. */
    private fun awaitUntil(timeoutMs: Long = 60_000, condition: () -> Boolean) {
        check(eventually(timeoutMs, condition)) { "the condition did not hold in time" }
    }

    /** True once [condition] holds, false when [timeoutMs] pass first. */
    private fun eventually(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            // The rule's effects, such as the one that starts the first sync, wait on its test dispatcher until the rule runs.
            compose.waitForIdle()
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(5)
        }
        return true
    }

    private companion object {
        const val PASSWORD = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)

        /** Monad testnet, the testnet that the owner's balances are on. */
        const val MONAD_TESTNET = 10143L
    }
}
