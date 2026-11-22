package app.lumio.send.solana

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import app.airvault.chains.solana.wallet.SolanaWallet
import app.airvault.core.model.AccountId
import app.lensly.app.VaultHolder
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingPayload
import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.ui.theme.LumenTheme
import app.lumio.data.config.ProviderConfig
import app.lumio.data.config.ProviderConfigStore
import app.lumio.data.net.Networks
import app.lumio.data.provider.solana.CustomSolanaRpc
import app.lumio.network.NetworkMode
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairImport
import app.lumio.pair.PairedWallets
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.Base58
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

/**
 * The setup request on screen is the request that Lensly signs, for as long as it is shown. A rebuild on a timer would replace it with a request that has
 * another blockhash and another request ID, so Lensly's answer to the request on screen would be refused as a different request.
 */
@RunWith(RobolectricTestRunner::class)
class SolanaSendRouteTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val blockhashReads = AtomicInteger()
    private val clock = FakeClock()

    @Before
    fun start() {
        server.dispatcher = rpc()
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun aSetupOnScreenIsNotRebuiltWhenItsWindowPasses() {
        val wallet = openVaultWithSolanaWallet()
        compose.setContent {
            LumenTheme {
                SolanaSendRoute(
                    navController = rememberNavController(),
                    holder = wallet.holder,
                    walletId = wallet.walletId,
                    accountId = wallet.accountId,
                    mode = NetworkMode.TESTNET,
                )
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(BALANCE).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(RECIPIENT)
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("0.1")
        compose.onNodeWithText("Review").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Show to Lensly").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Show to Lensly").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Scan Lensly's Signature").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, blockhashReads.get())

        // Longer than the 20-second wait that a rebuild used to run on, so any timer started at the fetch has come due.
        compose.mainClock.advanceTimeBy(21_000L)
        val rebuilt = runCatching { compose.waitUntil(timeoutMillis = 3_000) { blockhashReads.get() > 1 } }.isSuccess

        assertFalse(rebuilt, "The setup on screen was rebuilt at a new blockhash, so the request Lensly answers is no longer the one shown.")
        assertEquals(1, blockhashReads.get())
    }

    private class OpenWallet(val holder: VaultHolder, val walletId: String, val accountId: AccountId)

    /** A vault with one paired wallet that has a Solana account, and the Solana devnet reads sent to the local server. */
    private fun openVaultWithSolanaWallet(): OpenWallet {
        val vault = Vault(VaultLayout(File(tmp.root, "lumio-vault")), MemoryKeystore(), clock, KDF)
        return runBlocking {
            assertEquals(CreateResult.Created, vault.create(OPENER.toCharArray()))
            val holder = VaultHolder(vault)
            val session = unlocked(vault.unlock(OPENER.toCharArray()))
            holder.adopt(session, holder.unlockEpoch())
            val payload = PairFixtures.payloadOf(listOf(PairFixtures.ethereumAccount(0, "Main"), PairFixtures.solanaAccount(0, "Solana 0")))
            PairedWallets.save(session, roundTrip(payload), nowMs = 100L)
            val wallet = PairedWallets.list(session).single()
            ProviderConfigStore(session).save(mapOf(CustomSolanaRpc.idFor("devnet") to ProviderConfig(baseUrl = server.url("/").toString())))
            OpenWallet(
                holder = holder,
                walletId = wallet.id,
                accountId = AccountId.of(Networks.SOL_MAINNET, SolanaWallet.addresses.fromPublicKey(Networks.SOL_MAINNET, wallet.solana.single())),
            )
        }
    }

    private fun rpc(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = request.body?.utf8().orEmpty()
            return when {
                body.contains("\"getLatestBlockhash\"") -> {
                    blockhashReads.incrementAndGet()
                    answer("""{"context":{"slot":1},"value":{"blockhash":"$BLOCKHASH","lastValidBlockHeight":250}}""")
                }
                body.contains("\"getBalance\"") -> answer("""{"context":{"slot":1},"value":5000000000}""")
                body.contains("\"getAccountInfo\"") -> answer("""{"context":{"slot":1},"value":null}""")
                body.contains("\"getMinimumBalanceForRentExemption\"") -> answer("1447680")
                body.contains("\"getTokenAccountsByOwner\"") -> answer("""{"context":{"slot":1},"value":[]}""")
                else -> MockResponse.Builder().code(500).build()
            }
        }
    }

    private fun answer(result: String) = MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"result":$result}""").build()

    /** The pairing as Lumio reads it: the code is encoded as Lensly writes it, and checked by [PairImport]. */
    private fun roundTrip(payload: PairingPayload): PairingPayload =
        PairImport.of(UR(PairingCodec.UR_TYPE, PairingCodec.encode(payload, null)))

    private fun unlocked(result: UnlockResult): VaultSession {
        assertIs<UnlockResult.Unlocked>(result)
        return result.session
    }

    private companion object {
        const val OPENER = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val BALANCE = "Balance 5 SOL"
        val BLOCKHASH: String = Base58.encode(ByteArray(32) { 5 })
        val RECIPIENT: String = Base58.encode(ByteArray(32) { 9 })
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
