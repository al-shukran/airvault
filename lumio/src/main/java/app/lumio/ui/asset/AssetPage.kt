package app.lumio.ui.asset

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.price.ChartRange
import app.lumio.data.price.PriceSeries
import app.lumio.ui.kit.ChoiceGroup
import app.lumio.ui.kit.ChoiceRow
import app.lumio.ui.kit.ClearAirCapsuleSmall
import app.lumio.ui.kit.ClearAirChoiceSheet
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirIconButton
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.home.AssetIcon
import app.lumio.ui.home.AssetIconSpec

/**
 * A coin's holdings across the wallet: the amount, the accounts and chains it sits on, the logo of each chain in the order the holdings list gives them,
 * and its value in dollars when a price is read.
 */
internal data class CoinHeld(val amount: String, val summary: String, val value: String?, val chainIcons: List<AssetIconSpec> = emptyList())

/** A price's change over the chart's window: its text, and whether it rose. */
internal data class PriceChange(val text: String, val rising: Boolean)

/** An account that the Send and Receive pickers offer: its [title] and [detail], what it holds in [trailing], and what each action opens. */
internal class CoinAccount(val title: String, val detail: String, val trailing: String?, val onSend: () -> Unit, val onReceive: () -> Unit)

/** The picker that the Send or the Receive action opened. */
private enum class CoinPicker { SEND, RECEIVE }

/**
 * The Asset page (C4): one coin across the wallet, pushed from Home, Search and the watchlist. The top bar holds the back arrow, the coin's [name],
 * the watchlist star and the lock, and stays put while the rest scrolls. Under it come the price and its change, the tick chart, the range row, Send
 * and Receive, the holdings row that opens Holdings Breakdown, and the coin's activity. Send and Receive open a picker of the [accounts] that can hold
 * the coin. [held] is null for a coin nobody holds: the page then makes Receive the capsule, as the Account page does for a zero balance. A missing
 * [priceText] reads as a dash, because no price is read yet.
 */
@Composable
internal fun AssetPage(
    name: String,
    symbol: String,
    icon: @Composable () -> Unit,
    priceText: String?,
    change: PriceChange?,
    series: PriceSeries?,
    range: ChartRange,
    onRange: (ChartRange) -> Unit,
    watched: Boolean,
    onWatch: () -> Unit,
    held: CoinHeld?,
    onHoldings: () -> Unit,
    accounts: List<CoinAccount>,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onAllActivity: () -> Unit,
    activity: @Composable () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    var picker by remember { mutableStateOf<CoinPicker?>(null) }
    // The picker sheets are composed on the first open, not on the page's first frame. A tap composes them hidden, then the picker opens a frame
    // later, so the sheet still slides in.
    var sheetsComposed by rememberSaveable { mutableStateOf(false) }
    var opening by remember { mutableStateOf<CoinPicker?>(null) }
    fun open(which: CoinPicker) {
        sheetsComposed = true
        opening = which
    }
    LaunchedEffect(opening) {
        opening?.let {
            picker = it
            opening = null
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(vertical = ClearAirSpacing.md)) {
        AssetTopBar(name = name, watched = watched, onWatch = onWatch, onBack = onBack, onLock = onLock)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                icon()
                Spacer(Modifier.height(ClearAirSpacing.xs))
                ClearAirEyebrow(text = "$name · $symbol")
                Text(text = priceText ?: "—", style = ClearAirType.hero, color = palette.ink)
                change?.let { value ->
                    Text(
                        text = value.text,
                        style = ClearAirType.body,
                        color = if (value.rising) palette.vaultBlue else palette.rose,
                    )
                }
            }
            TickChart(series = series ?: PriceSeries(emptyList()), modifier = Modifier.padding(top = ClearAirSpacing.md))
            RangeRow(selected = range, onSelect = onRange, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (held == null) {
                    ClearAirCapsuleSmall(text = "Receive", onClick = { open(CoinPicker.RECEIVE) })
                    Spacer(Modifier.width(ClearAirSpacing.md))
                    ClearAirTextAction(text = "Send $symbol", onClick = { open(CoinPicker.SEND) })
                } else {
                    ClearAirCapsuleSmall(text = "Send $symbol", onClick = { open(CoinPicker.SEND) })
                    Spacer(Modifier.width(ClearAirSpacing.md))
                    ClearAirTextAction(text = "Receive", onClick = { open(CoinPicker.RECEIVE) })
                }
            }
            held?.let { coin ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter)) {
                    ClearAirEyebrow(text = "Your $symbol")
                }
                HeldRow(coin = coin, icon = icon, onClick = onHoldings)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ClearAirEyebrow(text = "Activity")
                ClearAirTextAction(text = "All", onClick = onAllActivity)
            }
            activity()
        }
    }
    if (sheetsComposed) {
        ClearAirSheet(visible = picker == CoinPicker.SEND, onClose = { picker = null }) {
            ClearAirChoiceSheet(
                title = "Send $symbol from",
                groups = listOf(
                    ChoiceGroup(
                        null,
                        accounts.map { account ->
                            ChoiceRow(
                                title = account.title,
                                detail = account.detail,
                                trailing = account.trailing,
                                onPick = { picker = null; account.onSend() },
                            )
                        },
                    ),
                ),
            )
        }
        ClearAirSheet(visible = picker == CoinPicker.RECEIVE, onClose = { picker = null }) {
            ClearAirChoiceSheet(
                title = "Receive $symbol to",
                groups = listOf(
                    ChoiceGroup(
                        null,
                        accounts.map { account ->
                            ChoiceRow(
                                title = account.title,
                                detail = account.detail,
                                trailing = account.trailing,
                                onPick = { picker = null; account.onReceive() },
                            )
                        },
                    ),
                ),
            )
        }
    }
}

/** The top bar of the Asset page: back, the coin's name, the watchlist star and the lock. The star is filled while the coin is watched. */
@Composable
private fun AssetTopBar(name: String, watched: Boolean, onWatch: () -> Unit, onBack: () -> Unit, onLock: () -> Unit) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClearAirIconButton(glyph = ClearAirGlyph.BACK, label = "Back", onClick = onBack)
        Text(
            text = name,
            modifier = Modifier.weight(1f).padding(horizontal = ClearAirSpacing.xs),
            style = ClearAirType.rowTitle,
            color = palette.ink,
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(role = Role.Checkbox, onClick = onWatch)
                .semantics {
                    contentDescription = if (watched) "Remove $name from the watchlist" else "Add $name to the watchlist"
                },
            contentAlignment = Alignment.Center,
        ) {
            ClearAirIcon(glyph = ClearAirGlyph.STAR, color = palette.ink, filled = watched)
        }
        ClearAirIconButton(glyph = ClearAirGlyph.LOCK, label = "Lock", onClick = onLock)
    }
}

/** The coin's holdings row, which opens Holdings Breakdown. Its value sits on the right when a price is read, and its chains' logos follow the count. */
@Composable
private fun HeldRow(coin: CoinHeld, icon: @Composable () -> Unit, onClick: () -> Unit) {
    val palette = LocalClearAirPalette.current
    val value = coin.value
    val chains: (@Composable () -> Unit)? = if (coin.chainIcons.isEmpty()) null else { { ChainDots(coin.chainIcons) } }
    if (value == null) {
        ClearAirRow(title = coin.amount, description = coin.summary, descriptionSuffix = chains, leading = icon, onClick = onClick)
    } else {
        ClearAirRow(
            title = coin.amount,
            description = coin.summary,
            descriptionSuffix = chains,
            leading = icon,
            trailing = { Text(text = value, style = ClearAirType.rowValue, color = palette.ink) },
            onClick = onClick,
        )
    }
}

/** The logo of each chain a coin sits on, small and overlapping, in the order the holdings list gives them. */
@Composable
private fun ChainDots(icons: List<AssetIconSpec>) {
    val palette = LocalClearAirPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(-CHAIN_DOT_OVERLAP)) {
        icons.forEach { icon ->
            Box(modifier = Modifier.size(CHAIN_DOT).clip(CircleShape).background(palette.background), contentAlignment = Alignment.Center) {
                AssetIcon(spec = icon, size = CHAIN_DOT)
            }
        }
    }
}

/** A chain's logo in the holdings row, and how far each one overlaps the one before it. */
private val CHAIN_DOT = 15.dp
private val CHAIN_DOT_OVERLAP = 4.dp

/** The chart ranges as mono text. The chosen one is bold with the spark's diamond under it, and tapping one chooses it. */
@Composable
private fun RangeRow(selected: ChartRange, onSelect: (ChartRange) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ChartRange.entries.forEach { range ->
            val chosen = range == selected
            Column(
                modifier = Modifier.clickable(role = Role.Tab, onClick = { onSelect(range) }).padding(vertical = ClearAirSpacing.xxs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = range.label,
                    style = ClearAirType.address.copy(fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal),
                    color = if (chosen) palette.ink else palette.ink2,
                )
                // Every range keeps the diamond's place, so the row does not move when the choice does.
                Box(
                    modifier = Modifier
                        .padding(top = ClearAirSpacing.xxs)
                        .size(RANGE_SPARK)
                        .rotate(45f)
                        .background(if (chosen) palette.spark else Color.Transparent),
                )
            }
        }
    }
}

/** The side of the spark under the chosen range: a square, turned into a diamond. */
private val RANGE_SPARK = 5.dp
