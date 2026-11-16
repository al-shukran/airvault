package app.lumio.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.EmptyState
import app.lensly.ui.components.HomeTopBar
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySuccess
import app.lensly.ui.wallet.WALLET_GUTTER
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle
import app.lumio.data.config.AssetSort
import app.lumio.data.config.DisplaySettings
import app.lumio.evm.EvmFormat
import app.lumio.home.AccountOption
import app.lumio.home.HomeAsset
import app.lumio.home.HomeAssets
import app.lumio.home.HomeFormat
import app.lumio.home.Masking
import app.lumio.home.TokenAsset
import app.lumio.network.NetworkMode
import app.lumio.ui.network.NetworkSwitch
import app.lumio.ui.network.label
import kotlinx.coroutines.delay

/** The test tag of the total. Its text is the total, or the mask while it is hidden. */
const val HOME_TOTAL_TAG = "home-total"

/** The test tag of the eye that shows a hidden total for [REVEAL_MS]. */
const val HOME_REVEAL_TAG = "home-reveal"

/** The test tags of the Send and Receive actions. */
const val HOME_SEND_TAG = "home-send"
const val HOME_RECEIVE_TAG = "home-receive"

/** The test tag of the sort control above the asset list. */
const val HOME_SORT_TAG = "home-sort"

/** How long a total that the owner revealed stays on screen before it is hidden again. The setting itself is not changed. */
const val REVEAL_MS = 15_000L

/** The test tag of an asset's row: the asset's ID after a prefix. */
fun assetRowTag(id: String): String = "asset-$id"

private const val NO_VALUE = "—"
private val LOGO_SIZE: Dp = 36.dp

/**
 * Lumio's Home: the total of the side's priced assets, then Send and Receive, then the asset list. [mode] is the side of the networks on show, switched
 * with [onMode]. The list holds the assets at or above the minimum in [settings], in its sort. An asset with no price is never hidden. The total is
 * masked until the owner turns it on, and the eye shows it for [REVEAL_MS] without changing the setting. A balance and a value are masked when the
 * setting for assets is off. [onAsset] opens an asset's page. Sorting is saved through [onUpdateDisplay]. Pull to refresh runs [onRefresh], and
 * [refreshing] shows that a read is running. [staleSinceMs] dates the prices that are kept from an earlier read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LumioHome(
    assets: List<HomeAsset>,
    settings: DisplaySettings,
    mode: NetworkMode,
    onMode: (NetworkMode) -> Unit,
    onUpdateDisplay: ((DisplaySettings) -> DisplaySettings) -> Unit,
    onLock: () -> Unit,
    onAsset: (HomeAsset) -> Unit,
    onPair: () -> Unit,
    hasWallets: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    staleSinceMs: Long? = null,
    notice: String? = null,
    sendOptions: List<AccountOption> = emptyList(),
    receiveOptions: List<AccountOption> = emptyList(),
    onSend: (AccountOption) -> Unit = {},
    onReceive: (AccountOption) -> Unit = {},
    networkEntry: (HomeAsset) -> NetworkEntry? = { null },
    tokenEntry: (HomeAsset) -> TokenEntry? = { null },
) {
    var revealed by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var sendOpen by remember { mutableStateOf(false) }
    var receiveOpen by remember { mutableStateOf(false) }
    LaunchedEffect(revealed) {
        if (revealed) {
            delay(REVEAL_MS)
            revealed = false
        }
    }
    val total = HomeAssets.totalUsd(assets)
    val shown = HomeAssets.sorted(HomeAssets.visible(HomeAssets.withoutSpam(assets, settings.hideSpam), settings.minimumUsd), settings.sort)

    Column(modifier = modifier.fillMaxSize()) {
        // The top bar stays above the list on both sides, so the lock and the wordmark stay in reach while the list scrolls.
        HomeTopBar(wordmark = "LUMIO", onLock = onLock, mark = { LumioMark() })
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LenslySpacing.lg)) {
                item(key = "mode") {
                    NetworkSwitch(
                        mode = mode,
                        onChange = onMode,
                        modifier = Modifier.padding(horizontal = WALLET_GUTTER, vertical = LenslySpacing.xs),
                    )
                }
                item(key = "total") {
                    TotalBlock(
                        total = total,
                        hidden = !settings.showTotal && !revealed,
                        canReveal = !settings.showTotal,
                        onReveal = { revealed = !revealed },
                        reading = refreshing,
                        staleSinceMs = staleSinceMs,
                    )
                }
                item(key = "actions") {
                    ActionRow(onSend = { sendOpen = true }, onReceive = { receiveOpen = true })
                }
                item(key = "header") { ListHeader(sort = settings.sort, onSort = { sortOpen = true }) }
                notice?.let { message ->
                    item(key = "notice") {
                        InlineBanner(
                            message = message,
                            tone = BannerTone.Warning,
                            modifier = Modifier.padding(horizontal = WALLET_GUTTER, vertical = LenslySpacing.xs),
                        )
                    }
                }
                when {
                    !hasWallets -> item(key = "empty") {
                        EmptyState(
                            icon = LenslyIcons.Wallets,
                            title = "No wallets paired yet",
                            body = "Pair a wallet from Lensly to see its balances here.",
                            actionLabel = "Pair from Lensly",
                            onAction = onPair,
                            modifier = Modifier.padding(horizontal = WALLET_GUTTER),
                        )
                    }
                    shown.isEmpty() -> item(key = "none") {
                        Text(
                            text = emptyListText(refreshing, assets.isNotEmpty(), settings.minimumUsd),
                            modifier = Modifier.padding(horizontal = WALLET_GUTTER, vertical = LenslySpacing.md),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(shown, key = { it.id }) { asset ->
                    AssetRow(
                        asset = asset,
                        network = networkEntry(asset),
                        token = tokenEntry(asset),
                        settings = settings,
                        onClick = { onAsset(asset) },
                    )
                }
            }
        }
    }

    if (sortOpen) {
        AssetSortSheet(
            sort = settings.sort,
            onSort = { next -> onUpdateDisplay { it.copy(sort = next) } },
            onDismiss = { sortOpen = false },
        )
    }
    if (sendOpen) {
        AccountPickSheet(
            title = "Send from",
            options = sendOptions,
            emptyText = "No wallet can send on ${mode.label()} yet. Pair a wallet from Lensly.",
            onPick = { option ->
                sendOpen = false
                onSend(option)
            },
            onDismiss = { sendOpen = false },
        )
    }
    if (receiveOpen) {
        AccountPickSheet(
            title = "Receive to",
            options = receiveOptions,
            emptyText = "No wallet can receive on ${mode.label()} yet. Pair a wallet from Lensly.",
            onPick = { option ->
                receiveOpen = false
                onReceive(option)
            },
            onDismiss = { receiveOpen = false },
        )
    }
}

/** The text of an empty list: a read in progress, no assets read yet, or every asset below the minimum. */
private fun emptyListText(refreshing: Boolean, anyAsset: Boolean, minimumUsd: Double): String = when {
    refreshing -> "Reading the network."
    !anyAsset -> "No balances read yet. Pull down to read them."
    else -> "Nothing is worth ${HomeFormat.usd(minimumUsd)} or more yet. Smaller balances are hidden, in Settings."
}

/** The total in dollars, its eye, and what the total is from. */
@Composable
private fun TotalBlock(
    total: Double?,
    hidden: Boolean,
    canReveal: Boolean,
    onReveal: () -> Unit,
    reading: Boolean,
    staleSinceMs: Long?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = WALLET_GUTTER, vertical = LenslySpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WalletEyebrow("Total")
            Spacer(Modifier.weight(1f))
            if (canReveal) {
                IconButton(onClick = onReveal, modifier = Modifier.testTag(HOME_REVEAL_TAG)) {
                    Icon(
                        imageVector = if (hidden) EyeIcons.Show else EyeIcons.Hide,
                        contentDescription = if (hidden) "Show the total" else "Hide the total",
                        tint = LenslyOnSurface,
                    )
                }
            }
        }
        Text(
            text = Masking.amount(total?.let { HomeFormat.usd(it) } ?: NO_VALUE, hidden),
            style = MaterialTheme.typography.headlineLarge,
            color = LenslyOnSurface,
            modifier = Modifier.testTag(HOME_TOTAL_TAG),
        )
        val note = when {
            reading -> "Reading the network"
            total == null -> "No price yet"
            staleSinceMs != null -> "Last known prices from ${EvmFormat.asOf(staleSinceMs)}"
            else -> null
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Send and Receive, the two actions of Home. Each opens a picker of the accounts it can use. */
@Composable
private fun ActionRow(onSend: () -> Unit, onReceive: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = WALLET_GUTTER, vertical = LenslySpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
    ) {
        WalletPill(
            text = "Send",
            onClick = onSend,
            modifier = Modifier.weight(1f).testTag(HOME_SEND_TAG),
            style = WalletPillStyle.Primary,
        )
        WalletPill(
            text = "Receive",
            onClick = onReceive,
            modifier = Modifier.weight(1f).testTag(HOME_RECEIVE_TAG),
            style = WalletPillStyle.Secondary,
        )
    }
}

/** The heading of the asset list, and the sort control that opens its sheet. */
@Composable
private fun ListHeader(sort: AssetSort, onSort: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = WALLET_GUTTER, end = WALLET_GUTTER, top = LenslySpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WalletEyebrow("Assets")
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onSort)
                .testTag(HOME_SORT_TAG)
                .padding(vertical = LenslySpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Sort: ${sortLabel(sort)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One asset: its logo, its coin and network, then its balance, its value and its 24-hour change. The balance and the value are masked when the
 * settings hide assets. A coin with no price shows a dash for its value and no change.
 */
@Composable
private fun AssetRow(asset: HomeAsset, network: NetworkEntry?, token: TokenEntry?, settings: DisplaySettings, onClick: () -> Unit) {
    val hidden = !settings.showAssets
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(assetRowTag(asset.id))
            .padding(horizontal = WALLET_GUTTER, vertical = LenslySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
    ) {
        AssetIcon(spec = assetIconSpec(asset, network, token), size = LOGO_SIZE)
        Column(modifier = Modifier.weight(1f)) {
            Text(asset.symbol, style = MaterialTheme.typography.titleMedium, color = LenslyOnSurface)
            Text(asset.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = Masking.amount(HomeFormat.coins(asset.balance), hidden),
                style = MaterialTheme.typography.bodyLarge,
                color = LenslyOnSurface,
            )
            Text(
                text = Masking.amount(asset.usd?.let { HomeFormat.usd(it) } ?: NO_VALUE, hidden),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            asset.change24h?.let { change ->
                Text(
                    text = HomeFormat.change(change),
                    style = MaterialTheme.typography.labelSmall,
                    color = changeColor(change),
                )
            }
        }
    }
}

/** The colour of a 24-hour change: green up, red down, and the muted text for no change. */
@Composable
private fun changeColor(change: Double) = when {
    change > 0 -> LenslySuccess
    change < 0 -> LenslyError
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
