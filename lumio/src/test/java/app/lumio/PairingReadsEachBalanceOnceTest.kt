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
import app.lumio.pair.PairedWallets
import app.lumio.wallets.WalletsBoard
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
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
 * Pairing a wallet reads each Ethereum balance once: the first sync that the pairing opens is the only read of it, so the Wallets home does not read
 * the same chain again when the pairing is saved. The owner's RPC endpoint counts the balance reads, one per shown address per refresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LumioApp::class)
class PairingReadsEachBalanceOnceTest {

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

    @Before
    fun serveRpc() {
        rpc.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
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
    fun pairingAWalletThatTheHomeHasReadReadsItsEthereumBalancesOnce() {
        val walletId = pairedWallet()
        val rpcUrl = rpc.url("/").toString()
        EvmSettingsStore.write(
            checkNotNull(holder.openSession()),
            EvmSettings(addressCount = 1, enabled = setOf(MONAD_TESTNET), rpc = mapOf(MONAD_TESTNET to ChainRpcs(custom = listOf(rpcUrl), active = rpcUrl))),
        )
        setGraph()
        // The Wallets home is open on the testnet side, as it is once the vault opens.
        val vaultAttachment = checkNotNull(holder.sessionAttachment() as? LumioVault)
        val home = WalletsBoard(app, vaultAttachment).also { vaultAttachment.wallets = it }
        home.showSide(NetworkMode.TESTNET, listVersion = 0)
        awaitUntil { home.view != null }

        // The owner pairs the same wallet again: the review saves it, and the first sync of the wallet opens.
        compose.runOnUiThread {
            draft.pairing = PairFixtures.payloadOf(listOf(PairFixtures.ethereumAccount(0, "Account 0")))
            nav.navigate(LumioRoute.PAIR_REVIEW)
        }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Update").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Update").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { currentRoute() == LumioRoute.FIRST_SYNC }
        val imports = vaultAttachment.firstSyncs
        compose.waitUntil(timeoutMillis = 10_000) { imports.get(walletId, NetworkMode.TESTNET) != null }
        val sync = checkNotNull(imports.get(walletId, NetworkMode.TESTNET)).sync
        awaitUntil { FirstSync.isSettled(sync.rows.value) && !home.refreshing }

        assertEquals(1, balanceReads.get(), "The Ethereum address is read once for the pairing, not once by the home and again by the first sync")
    }

    /** An open vault with one paired wallet, saved as the pairing review saves it. */
    private fun pairedWallet(): String = runBlocking {
        assertEquals(GateOutcome.Opened, createFirstVault(holder, PASSWORD.toCharArray(), context))
        val session = checkNotNull(holder.openSession())
        PairedWallets.save(session, PairFixtures.payloadOf(listOf(PairFixtures.ethereumAccount(0, "Account 0"))), nowMs = 100L).id
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

    /**
     * Runs the main looper until [condition] holds, as the board's own tests do: its reads resume on the main thread. It also runs the
     * compose rule's pending work, because the rule's effects (the one that starts the first sync) wait on its test dispatcher.
     */
    private fun awaitUntil(timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            check(System.currentTimeMillis() < deadline) { "the condition did not hold in time" }
            Thread.sleep(5)
        }
    }

    private companion object {
        const val PASSWORD = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)

        /** Monad testnet, the testnet that the owner's balances are on. */
        const val MONAD_TESTNET = 10143L
    }
}
