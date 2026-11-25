package app.lumio

import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import app.airvault.core.model.AccountId
import app.lensly.app.export.PairingPayload
import app.lensly.app.vaultHolder
import app.lumio.btc.networkId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.Provider
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxQuery
import app.lumio.demo.DemoContext
import app.lumio.demo.DemoFixtures
import app.lumio.demo.DemoProfile
import app.lumio.demo.DemoProvider
import app.lumio.demo.DemoScreen
import app.lumio.demo.DemoScreens
import app.lumio.network.NetworkMode
import app.lumio.send.SubmitFailure
import app.lumio.send.SubmitMachine
import java.io.IOException
import app.lumio.pair.PairedWallets
import app.lensly.core.btc.ScriptType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Debug-only demo data mode. `adb shell am start -n app.lumio/.MainActivity --ez debugDemoData true --es debugScreen p1-00` swaps every network
 * source for the fixtures of the approved mockups (see [DemoProvider]) and opens the mockup frame `p1-00` directly. The extra `debugProfile`
 * (`full`, `empty`, `offline`, `error`, `slow`) changes how the sources answer. It works on an emulator only, as [DebugDemoSeed] does.
 *
 * This file lives in the debug source set only. The release and benchmark source sets have stubs that change nothing, so release builds never
 * contain the extras' names or the fixtures.
 */
internal object DebugDemoData {
    private const val TAG = "DebugDemoData"
    private const val EXTRA_DATA = "debugDemoData"
    private const val EXTRA_SCREEN = "debugScreen"
    private const val EXTRA_PROFILE = "debugProfile"

    @Volatile
    private var enabled = false

    @Volatile
    private var screen: DemoScreen? = null

    @Volatile
    private var profile = DemoProfile.FULL

    /** The profile the launch asked for. It takes effect when [settle] runs, so the seed always reads the full demo values first. */
    private var requested = DemoProfile.FULL

    private val provider = DemoProvider { profile }

    val active: Boolean get() = enabled

    val mode: NetworkMode? get() = if (enabled) screen?.mode ?: NetworkMode.MAINNET else null

    /** Reads the demo extras of [intent]. A launch with none leaves the mode as it was. */
    fun offer(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_SCREEN)
        if (!intent.getBooleanExtra(EXTRA_DATA, false) && id == null) return
        enabled = true
        screen = DemoScreens.byId(id)
        requested = intent.getStringExtra(EXTRA_PROFILE)?.let { name -> DemoProfile.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            ?: screen?.profile ?: DemoProfile.FULL
        profile = DemoProfile.FULL
    }

    /** Applies the requested profile. The seed calls it once the demo balances are read. */
    fun settle() {
        profile = requested
    }

    /** The demo provider in place of [real] while the mode is on. */
    fun providers(real: List<Provider>): List<Provider> = if (enabled) listOf(provider) else real

    /** True when the open frame asks for [key], a sheet or a state of its screen. */
    fun flag(key: String): Boolean = screen?.flags?.contains(key) == true

    /** The text a frame prefills for the field [key], so the secure keyboard is never typed. Empty when there is none. */
    fun text(key: String): String = if (flag("prefill")) PASSWORD else ""

    /** The pairing that a Pair Review frame shows before any scan: the wallet again for a re-pair, else another wallet. Null for any other frame. */
    fun pairing(): PairingPayload? = if (!flag("pair:review")) null else DemoFixtures.payload().let { demo ->
        if (flag("pair:repair")) demo else demo.copy(fingerprint = NEW_FINGERPRINT, name = "Air 2", autoNameBase = "Air 2")
    }

    /** The step of the send that the open frame asks for: the part of its `send:` flag after the colon, or null. */
    fun sendStage(): String? = screen?.flags?.lastOrNull { it.startsWith("send:") }?.removePrefix("send:")?.let { stage ->
        // A fee sheet frame opens the amount step with its sheet.
        if (stage == "amount" && flag("fee:advanced")) "feeAdvanced" else stage
    } ?: if (screen?.id == "p1-10") "recipient" else null

    /** A signed send of the demo: its broadcast answers with the transaction's hash, or fails as a refused one does. */
    fun signedMachine(failed: Boolean): SubmitMachine<ByteArray, String> = SubmitMachine(
        signed = ByteArray(1),
        broadcast = { raw -> if (failed) throw IOException("The demo network refused the transaction.") else "0x" + raw.joinToString("") { "%02x".format(it) }.padEnd(64, 'a') },
        describe = { SubmitFailure("The transaction could not be sent. Check the balance, then try again.") },
    )

    /** The Last Chance countdown: held long while the frame is Last Chance, so the page can be captured. */
    fun lastChanceSeconds(seconds: Int, hold: Boolean): Int = if (hold) HOLD_SECONDS else seconds

    /** Opens the frame's route over the Accounts home once the seed has finished, and selects its side. */
    @Composable
    fun Entry(controller: NavHostController, selection: NetworkSelection, request: Int) {
        val target = screen ?: return
        if (!enabled || request == 0) return
        val app = LocalContext.current.applicationContext as LumioApp
        val holder = LocalContext.current.vaultHolder()
        val entry by controller.currentBackStackEntryAsState()
        val atHome = entry?.destination?.route == LumioRoute.WALLETS
        val opened = remember(request) { booleanArrayOf(false) }
        LaunchedEffect(request, atHome) {
            if (!atHome || opened[0]) return@LaunchedEffect
            opened[0] = true
            selection.mode = target.mode
            val context = withContext(Dispatchers.IO) { contextOf(app, holder, target.mode) } ?: return@LaunchedEffect
            controller.navigate(target.route(context)) { launchSingleTop = true }
            Log.i(TAG, "opened ${target.id}")
        }
    }

    /** What the routes need from the seeded vault. The history of the demo account is read first, so a transaction page has rows to open. */
    private suspend fun contextOf(app: LumioApp, holder: app.lensly.app.VaultHolder, mode: NetworkMode): DemoContext? {
        val session = holder.openSession() ?: return null
        val wallet = PairedWallets.list(session).firstOrNull { it.name == DemoFixtures.WALLET_NAME } ?: return null
        val chain = if (mode == NetworkMode.TESTNET) DemoFixtures.MONAD_TESTNET else DemoFixtures.ETHEREUM
        val evm = wallet.evmAccounts.map { AccountId.of(Networks.evm(chain), it.address) }
        val btc = wallet.bitcoin.first { it.network == mode.btcNetwork() && it.scriptType == ScriptType.NATIVE_SEGWIT }
        val btcId = AccountId.of(btc.network.networkId().chain, btc.receiveAddress(0))
        var sent: String? = null
        var token: String? = null
        val vault = app.lumioVault()
        if (vault != null && wallet.evmAccounts.isNotEmpty()) {
            val main = NetworkId(Networks.evm(DemoFixtures.ETHEREUM), testnet = false)
            runCatching { app.txHistory(vault).refresh(main, wallet.evmAccounts[0].address) }
            repeat(HISTORY_TRIES) {
                val rows = vault.txs.page(TxQuery(), 0, HISTORY_PAGE)
                sent = rows.firstOrNull { it.direction == TxDirection.OUT && it.asset.contract == null }?.let { "${it.account}|${it.id}" }
                token = rows.firstOrNull { it.direction == TxDirection.IN && it.asset.contract != null }?.let { "${it.account}|${it.id}" }
                if (sent != null && token != null) return@repeat
                delay(HISTORY_WAIT_MS)
            }
        }
        return DemoContext(wallet.id, mode, evm, btcId, sent, token)
    }

    /** The App Password that the demo vault and the password pages of a frame show filled in. It is the public demo password. */
    private const val PASSWORD = "Vq7m-Lp2x-Tz9w-Kd4n"
    private const val HOLD_SECONDS = 600
    private const val NEW_FINGERPRINT = 0x1A2B3C4DL
    private const val HISTORY_TRIES = 6
    private const val HISTORY_PAGE = 100
    private const val HISTORY_WAIT_MS = 500L
}
