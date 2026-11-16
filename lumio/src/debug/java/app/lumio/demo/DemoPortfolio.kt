package app.lumio.demo

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.lensly.core.vault.VaultSession
import app.lumio.data.asset.AssetId
import app.lumio.data.price.ChartRange
import app.lumio.data.price.Price
import app.lumio.data.price.PriceSeries
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxQuery
import app.lumio.home.HomeAsset
import app.lumio.network.NetworkMode
import app.lumio.pair.PairedWallets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Debug-only: a fixed portfolio for design review. When its switch is on, Home, the asset page, the Transactions tab and the price reads show
 * [DemoAssets] and [DemoLedger] instead of the vault and the network. The repositories call the functions here, so no screen checks the switch.
 *
 * The switch is a Developer row at the bottom of Settings. It is on until the owner sets it, and until then it is on only while no wallet is paired.
 * It lives in the app's own preferences, not in the vault, so it can be read before the vault opens. A debug launch is the only place it is set.
 *
 * This file lives in the debug source set only. The release stub has the same functions, all of them empty, so release builds read the network.
 */
internal object DemoPortfolio {
    private const val PREFS = "lumio-demo"
    private const val KEY = "portfolio"

    private val on = MutableStateFlow(false)
    private var prefs: SharedPreferences? = null

    /** The switch. It is off until [attach] has read it. */
    val enabled: StateFlow<Boolean> = on.asStateFlow()

    /**
     * Reads the switch from the app's preferences. Called once, when the application starts. A switch the owner has never set is off here: the
     * default is decided when a vault opens, so unit tests and any code that runs without a vault never see the demo.
     */
    fun attach(app: Application) {
        val store = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        on.value = store.getBoolean(KEY, false)
    }

    /**
     * Called when a vault opens. Until the owner has set the switch, it is on only while the vault holds no paired wallet, so a debug build shows the
     * demo on a fresh install and leaves a real wallet's own balances alone.
     */
    suspend fun vaultOpened(session: VaultSession) {
        val store = prefs ?: return
        if (!store.contains(KEY)) on.value = withContext(Dispatchers.IO) { PairedWallets.list(session).isEmpty() }
    }

    /** Sets the switch and keeps it. */
    fun setOn(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY, value)?.apply()
        on.value = value
    }

    /** True while the demo replaces the vault and the network. */
    fun isOn(): Boolean = on.value

    /** The holdings of [mode]'s side, or null when the switch is off. */
    fun homeAssets(mode: NetworkMode): List<HomeAsset>? =
        if (isOn()) DemoAssets.holdings.filter { mode.holds(it.testnet) } else null

    /** The demo price of [asset], or null when the switch is off or the demo does not price it. */
    fun price(asset: AssetId): Price? = if (isOn()) DemoAssets.price(asset, System.currentTimeMillis()) else null

    /** The demo chart of [asset] over [range] ending at [nowMs], or null when the switch is off or the demo has no chart for it. */
    fun chart(asset: AssetId, range: ChartRange, nowMs: Long): PriceSeries? = if (isOn()) DemoAssets.series(asset, range, nowMs) else null

    /** Every demo transaction that [query] matches, in its order, or null when the switch is off. */
    fun transactions(query: TxQuery): List<LumioTx>? = if (isOn()) DemoLedger.matching(DemoLedger.rows, query) else null

    /** The demo transaction [txId] of [account], or null when the switch is off or the demo does not hold it. */
    fun transaction(account: String, txId: String): LumioTx? =
        if (isOn()) DemoLedger.rows.firstOrNull { it.id == txId && it.account.equals(account, ignoreCase = true) } else null

    /** The switch row of the Developer section of Settings. [onChanged] runs after each change, so Home reads the list again. */
    fun developerSection(onChanged: () -> Unit): (@Composable () -> Unit)? = { DemoPortfolioRow(onChanged) }

    /** The state the Developer row shows under its title. It follows the switch, so the row reads on or off as the owner leaves the page. */
    @Composable
    fun developerSummary(): String? {
        val checked by enabled.collectAsState()
        return if (checked) "Demo portfolio on" else "Demo portfolio off"
    }

    @Composable
    private fun DemoPortfolioRow(onChanged: () -> Unit) {
        val checked by enabled.collectAsState()
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Demo portfolio",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Sample balances, prices and transactions on Home and Transactions. Debug builds only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = {
                    setOn(it)
                    onChanged()
                },
                modifier = Modifier.testTag("demo-portfolio"),
            )
        }
    }
}
