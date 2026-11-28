package app.lumio.ui.wallets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.app.familyLabel
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.Clock
import app.lumio.data.WallClock
import app.lumio.data.net.Networks
import app.lumio.home.HomeFormat
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.home.AssetDisc
import app.lumio.ui.kit.AddressLine
import app.lumio.ui.kit.ClearAirAmount
import app.lumio.ui.kit.ClearAirCapsuleSmall
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirRefreshBox
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSkeleton
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.kit.ClearAirTextTabs
import app.lumio.ui.kit.HiddenSmallLine
import app.lumio.ui.kit.LocalFiat
import app.lumio.ui.kit.rememberCopyAction
import app.lumio.ui.transactions.ActivityRowItem
import app.lumio.wallets.WalletAccount
import java.time.ZoneId

/** The bar a total takes while no read of the account's balance is kept. */
private val TOTAL_BAR_WIDTH = 150.dp
private val TOTAL_BAR_HEIGHT = 26.dp

/** The text that an account's assets or activity show while nothing has been read for them. */
private const val NOT_READ = "Not read yet"

/** The sections of an account: its assets and its activity, as text tabs. */
private enum class AccountTab(val label: String) {
    ASSETS("Assets"),
    ACTIVITY("Activity"),
}

/**
 * One account (C3): its name and its wallet's name as the identity line, its address in groups of four, and its total with the freshness line. Send
 * is a compact capsule with Receive beside it as the text action, and the two swap places when the account holds nothing. Assets and Activity are text
 * tabs under them. Pulling down reads the account again. A balance no read has reached is a bar, and the rows under it keep their places.
 */
@Composable
internal fun AccountScreen(
    walletName: String,
    account: WalletAccount,
    showBalance: Boolean,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whether a row worth this many dollars, or unknown, is left out of the asset list. The total above still counts it. */
    hidesSmall: (Double?) -> Boolean = { false },
    assets: List<AssetRowModel> = emptyList(),
    activity: List<TxFeedItem> = emptyList(),
    names: Map<String, String> = emptyMap(),
    onAsset: (AssetRowModel) -> Unit = {},
    onAddAsset: (() -> Unit)? = null,
    onOpenTransaction: (TxFeedItem) -> Unit = {},
    refreshing: Boolean = false,
    refreshByPull: Boolean = false,
    updatedAtMs: Long? = null,
    notice: String? = null,
    onRefresh: () -> Unit = {},
    loadingAssets: Boolean = false,
    loadingActivity: Boolean = false,
    clock: Clock = WallClock,
    zone: ZoneId = ZoneId.systemDefault(),
    online: Boolean = true,
) {
    val balance = account.balance
    val heroText = balance?.let { "${HomeFormat.coins(it.balance)} ${it.symbol}" }
    val usd = assets.firstOrNull { it.isNative }?.usd
    var tab by rememberSaveable { mutableStateOf(AccountTab.ASSETS) }
    var showSmall by rememberSaveable { mutableStateOf(false) }
    // The assets that Hide Small Balances leaves out of the list. The total above counts every asset.
    val listed = if (showSmall) assets else assets.filterNot { hidesSmall(it.usd) }
    ClearAirRefreshBox(refreshByPull = refreshByPull, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = ClearAirSpacing.md)) {
            item(key = "identity") {
                AccountIdentity(line = identityLine(account, walletName), address = account.address)
            }
            item(key = "total") {
                AccountTotal(
                    heroText = heroText,
                    usd = usd,
                    showBalance = showBalance,
                    refreshing = refreshing,
                    updatedAtMs = updatedAtMs,
                    clock = clock,
                )
            }
            item(key = "actions") {
                AccountActions(online = online, onSend = onSend, onReceive = onReceive)
            }
            notice?.let { message ->
                item(key = "notice") {
                    WalletNotice(message = message, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
                }
            }
            item(key = "tabs") {
                ClearAirTextTabs(
                    options = AccountTab.entries,
                    selected = tab,
                    label = { it.label },
                    onSelect = { tab = it },
                    modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter),
                )
            }
            when (tab) {
                AccountTab.ASSETS -> assetItems(
                    account = account,
                    assets = listed,
                    hiddenSmall = assets.size - listed.size,
                    loadingAssets = loadingAssets,
                    showBalance = showBalance,
                    onAsset = onAsset,
                    onAddAsset = onAddAsset,
                    onShowSmall = { showSmall = true },
                )
                AccountTab.ACTIVITY -> activityItems(
                    account = account,
                    activity = activity,
                    names = names,
                    loadingActivity = loadingActivity,
                    showBalance = showBalance,
                    zone = zone,
                    onOpenTransaction = onOpenTransaction,
                )
            }
        }
    }
}

/** The identity line of an account: its label, its group and its wallet, or its script when it has one, such as "Trading · Group DeFi · Air 1". */
internal fun identityLine(account: WalletAccount, walletName: String): String =
    listOfNotNull(account.label, account.group, walletName, account.script).joinToString(" · ")

/** The identity of an account: its line, and its address under it. */
@Composable
private fun AccountIdentity(line: String, address: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs)) {
        Text(text = line, style = ClearAirType.body, color = LocalClearAirPalette.current.ink2)
        AccountAddress(address = address)
    }
}

/**
 * The address of an account as the page writes it: its first and last two groups of four, with an ellipsis between, such as "0x1020 3040 … 8191 122b".
 * A tap copies the whole address, and the line reads "Address copied" for 1.5 s in its place.
 */
@Composable
private fun AccountAddress(address: String) {
    val palette = LocalClearAirPalette.current
    val copy = rememberCopyAction(value = address, label = "Address")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ClearAirSpacing.xxs)
            .lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = copy.copy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (copy.copied) {
            Text(text = "Address copied", style = ClearAirType.label, color = palette.ink2)
        } else {
            AddressLine(address = address, color = palette.ink, ellipsisColor = palette.ink2, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(ClearAirSpacing.xs))
            ClearAirIcon(glyph = ClearAirGlyph.COPY, color = palette.ink2)
        }
    }
}

/** The total of the account: "Total" over its coins, the dollars under them, and the freshness line. A balance no read has reached is a bar. */
@Composable
private fun AccountTotal(heroText: String?, usd: Double?, showBalance: Boolean, refreshing: Boolean, updatedAtMs: Long?, clock: Clock) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs)) {
        ClearAirEyebrow(text = "Total")
        if (heroText == null) {
            ClearAirSkeleton(modifier = Modifier.width(TOTAL_BAR_WIDTH).height(TOTAL_BAR_HEIGHT))
        } else {
            ClearAirAmount(text = heroText, hidden = !showBalance, style = ClearAirType.total)
        }
        usd?.let { value ->
            ClearAirAmount(text = LocalFiat.current.amount(value), hidden = !showBalance, style = ClearAirType.rowValue, color = LocalClearAirPalette.current.ink2)
        }
        FreshnessLine(refreshing = refreshing, updatedAtMs = updatedAtMs, clock = clock, modifier = Modifier.padding(top = ClearAirSpacing.xxs))
    }
}

/**
 * Send and Receive, side by side under the total: Send is the compact capsule and Receive the text action, each with its arrow. Offline, Send is
 * disabled and says why under it, and Receive stays live.
 */
@Composable
private fun AccountActions(online: Boolean, onSend: () -> Unit, onReceive: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClearAirCapsuleSmall(text = "Send", onClick = onSend, enabled = online, icon = ClearAirGlyph.SEND)
            Spacer(Modifier.width(ClearAirSpacing.md))
            ClearAirTextAction(text = "Receive", onClick = onReceive, icon = ClearAirGlyph.RECEIVE)
        }
        if (!online) {
            Text(text = "Sending needs a connection", style = ClearAirType.body, color = LocalClearAirPalette.current.ink2)
        }
    }
}

/**
 * The assets of the account: one row each, a bar for each row a read has not landed, and "Add Asset" for an Algorand account. When Hide Small Balances
 * leaves out every asset, the line says how many are hidden, and Show lists them ([hiddenSmall] counts the ones left out).
 */
private fun LazyListScope.assetItems(
    account: WalletAccount,
    assets: List<AssetRowModel>,
    hiddenSmall: Int,
    loadingAssets: Boolean,
    showBalance: Boolean,
    onAsset: (AssetRowModel) -> Unit,
    onAddAsset: (() -> Unit)?,
    onShowSmall: () -> Unit,
) {
    if (assets.isEmpty()) {
        item(key = "assets-empty") {
            if (loadingAssets) {
                Column {
                    LoadingRow()
                    LoadingRow()
                }
            } else if (hiddenSmall > 0) {
                HiddenSmallLine(count = hiddenSmall, onShow = onShowSmall)
            } else {
                EmptyLine(if (account.balance == null) NOT_READ else "No assets yet.")
            }
        }
    } else {
        itemsIndexed(assets, key = { _, asset -> asset.assetKey }) { index, asset ->
            ClearAirRow(
                title = asset.symbol,
                description = asset.network,
                leading = { AssetDisc(asset.icon) },
                trailing = { AssetValue(asset = asset, shown = showBalance) },
                onClick = { onAsset(asset) },
                divider = index > 0,
                chevron = false,
            )
        }
        if (loadingAssets) {
            item(key = "assets-loading") { LoadingRow() }
        }
    }
    onAddAsset?.let { addAsset ->
        item(key = "add-asset") { ClearAirRow(title = "Add Asset", onClick = addAsset, divider = assets.isNotEmpty()) }
    }
}

/** The activity of the account: its newest transactions, a bar while they are read, or the line that says there are none yet. */
private fun LazyListScope.activityItems(
    account: WalletAccount,
    activity: List<TxFeedItem>,
    names: Map<String, String>,
    loadingActivity: Boolean,
    showBalance: Boolean,
    zone: ZoneId,
    onOpenTransaction: (TxFeedItem) -> Unit,
) {
    when {
        account.namespace == Networks.SOLANA || account.namespace == Networks.ALGORAND ->
            item(key = "activity-none") { EmptyLine("Activity is not read for ${familyLabel(account.namespace)} yet.") }
        activity.isEmpty() && loadingActivity -> item(key = "activity-loading") { LoadingRow() }
        activity.isEmpty() -> item(key = "activity-empty") { EmptyLine("No activity yet. Your first receive will appear here.") }
        else -> itemsIndexed(activity, key = { _, entry -> entry.tx.id }) { index, entry ->
            if (index > 0) RowDivider(indent = ROW_DIVIDER_INDENT)
            ActivityRowItem(item = entry, names = names, zone = zone, shown = showBalance, onClick = { onOpenTransaction(entry) })
        }
    }
}

/** The amount and value of an asset at the right of its row. A hidden balance shows only the mask. */
@Composable
private fun AssetValue(asset: AssetRowModel, shown: Boolean) {
    Column(horizontalAlignment = Alignment.End) {
        ClearAirAmount(text = asset.amountText, hidden = !shown, style = ClearAirType.rowValue)
        asset.usd?.let { usd ->
            ClearAirAmount(text = LocalFiat.current.amount(usd), hidden = !shown, style = ClearAirType.label, color = LocalClearAirPalette.current.ink2)
        }
    }
}

/** A line of a list that says there is nothing to show yet. */
@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm),
        style = ClearAirType.body,
        color = LocalClearAirPalette.current.ink2,
    )
}
