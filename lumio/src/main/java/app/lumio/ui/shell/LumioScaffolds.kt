package app.lumio.ui.shell

import app.lumio.DebugDemoData
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lumio.network.NetworkMode
import app.lumio.ui.kit.ClearAirPageTopBar
import app.lumio.ui.kit.ClearAirRootTopBar
import app.lumio.ui.kit.ClearAirStatusBand
import app.lumio.ui.kit.ClearAirTabBar
import app.lumio.ui.kit.LumioTab
import app.lumio.ui.kit.WalletEmblemTone
import app.lumio.ui.network.PanelWallet
import app.lumio.ui.network.ProfilePanel
import app.lumio.ui.kit.ClearAirSheet
import java.util.Date

/**
 * The root of a tab: the root top bar, the offline band under it when [offline] is set, the tab's [content], and the tab bar. The top bar's emblem
 * and title open the profile panel, and its search icon opens the search sheet. Both overlays belong to this root, and only one of them is open at a
 * time, so a sheet never opens over the panel or over another sheet.
 */
@Composable
fun LumioRootScreen(
    tab: LumioTab,
    title: String = tab.label,
    mode: NetworkMode,
    onMode: (NetworkMode) -> Unit,
    panelWallets: List<PanelWallet>,
    activeWalletId: String?,
    onWallet: (String) -> Unit,
    testNetworks: Int,
    networkSentence: String,
    offline: String?,
    onTab: (LumioTab) -> Unit,
    onScan: () -> Unit,
    scanNote: String? = null,
    scanReady: Boolean = true,
    onTestNetworks: () -> Unit,
    onPair: () -> Unit,
    onLock: () -> Unit,
    search: @Composable (onClose: () -> Unit) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var panelOpen by remember { mutableStateOf(DebugDemoData.flag("panel")) }
    var searchOpen by remember { mutableStateOf(DebugDemoData.flag("search")) }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ClearAirRootTopBar(
                title = title,
                switcher = tab == LumioTab.HOME,
                emblem = panelWallets.firstOrNull { it.id == activeWalletId }?.tone ?: WalletEmblemTone.BLUE,
                onPanel = { panelOpen = true },
                onSearch = { searchOpen = true },
                onLock = onLock,
            )
            offline?.let { ClearAirStatusBand(text = it) }
            Column(modifier = Modifier.weight(1f).fillMaxWidth(), content = content)
            ClearAirTabBar(selected = tab, onSelect = onTab)
        }
        ProfilePanel(
            visible = panelOpen,
            mode = mode,
            testNetworks = testNetworks,
            networkSentence = networkSentence,
            scanNote = scanNote,
            scanReady = scanReady,
            wallets = panelWallets,
            activeWalletId = activeWalletId,
            onMode = onMode,
            onScan = onScan,
            onTestNetworks = onTestNetworks,
            onWallet = onWallet,
            onPair = onPair,
            onDismiss = { panelOpen = false },
        )
        ClearAirSheet(visible = searchOpen, onClose = { searchOpen = false }, top = 0.dp) {
            search { searchOpen = false }
        }
    }
}

/**
 * A page of the shell: its page top bar with a back arrow to [backLabel] (a plain back arrow when [backLabel] is null), the offline band when [offline] is
 * set, and the page's [content]. A page has no tab bar, and its back arrow, the system back and predictive back all return one page. [sheet] is the
 * page's one sheet, drawn over the whole page. A page that ends its flow shows a × ([close]) in place of the arrow.
 */
@Composable
fun LumioPageScreen(
    title: String,
    backLabel: String?,
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
    sheet: @Composable () -> Unit = {},
    close: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ClearAirPageTopBar(title = title, backLabel = backLabel, onBack = onBack, onLock = onLock, close = close)
            offline?.let { ClearAirStatusBand(text = it) }
            Column(modifier = Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars), content = content)
        }
        sheet()
    }
}

/**
 * Whether the device has a network with internet access, and it changes as the network does. It starts from the active network, so the first frame
 * already says whether Lumio is offline.
 */
@Composable
fun rememberOnline(): Boolean {
    val manager = LocalContext.current.getSystemService(ConnectivityManager::class.java)
    var online by remember(manager) { mutableStateOf(manager.hasInternet()) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }

            override fun onLost(network: Network) {
                online = manager.hasInternet()
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return online
}

/** True when the active network has internet access. */
private fun ConnectivityManager.hasInternet(): Boolean {
    val capabilities = getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/**
 * The offline band's words: the time the shown balances were read, as the 24-hour clock writes it, so the owner knows how old the numbers are. With
 * no read yet, it says only that Lumio is offline.
 */
fun offlineBand(balancesReadAtMs: Long?): String {
    if (balancesReadAtMs == null) return "Offline"
    return "Offline · showing balances from ${DateFormat.format("HH:mm", Date(balancesReadAtMs))}"
}
