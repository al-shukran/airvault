package app.lumio.ui.asset

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.price.ChartFrame
import app.lumio.data.price.PriceSeries
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.home.ChainDots
import app.lumio.ui.kit.ChoiceGroup
import app.lumio.ui.kit.ChoiceRow
import app.lumio.ui.kit.ClearAirAmount
import app.lumio.ui.kit.ClearAirChangeLineStyle
import app.lumio.ui.kit.ClearAirChoiceSheet
import app.lumio.ui.kit.ClearAirDisc
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIconButton
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.kit.cssLine

/**
 * A coin's holdings across the wallet: the amount, the accounts and chains it sits on, the logo of each chain in the order the holdings list gives them,
 * and its value in dollars when a price is read.
 */
internal data class CoinHeld(val amount: String, val summary: String, val value: String?, val chainIcons: List<AssetIconSpec> = emptyList())

/** A price's change over the chart's window: its text, and whether it rose. */
internal data class PriceChange(val text: String, val rising: Boolean)

/** An account that the Send and Receive pickers offer: its [title] and [detail], what it holds in [trailing], and what each action opens. A null [onSend] is an account that cannot send the coin. */
internal class CoinAccount(val title: String, val detail: String, val trailing: String?, val onSend: (() -> Unit)?, val onReceive: () -> Unit)

/** The picker that the Send or the Receive action opened. */
private enum class CoinPicker { SEND, RECEIVE }

/**
 * The Asset page (C4): one coin across the wallet, pushed from Home, Search and the watchlist. The top bar holds the back arrow, the coin's [name],
 * the watchlist star and the lock, and stays put while the rest scrolls. Under it come the price and its change, the tick chart, the range row, Send
 * and Receive, the holdings row that opens Holdings Breakdown, and the coin's activity. Send and Receive open a picker of the [accounts] that can hold
 * the coin. [held] is null for a coin nobody holds: the page then makes Receive the capsule, as the Account page does for a zero balance. A missing
 * [priceText] reads as a dash, because no price is read yet. With [priceOnly], which is a coin opened with no wallet paired, the page keeps the price, the
 * chart and the range row, and leaves out Send and Receive, the holdings and the activity.
 */
@Composable
internal fun AssetPage(
    name: String,
    symbol: String,
    icon: @Composable (Dp) -> Unit,
    priceText: String?,
    change: PriceChange?,
    series: PriceSeries?,
    range: ChartFrame,
    onRange: (ChartFrame) -> Unit,
    watched: Boolean,
    onWatch: () -> Unit,
    priceOnly: Boolean = false,
    held: CoinHeld?,
    onHoldings: () -> Unit,
    accounts: List<CoinAccount>,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onAllActivity: () -> Unit,
    activity: @Composable () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    val senders = accounts.filter { it.onSend != null }
    // The frame's text action has 6 dp at each side, and its label sits 1 dp higher than Compose sets it in its line.
    val textActionModifier = Modifier.padding(horizontal = TEXT_ACTION_PAD).offset(y = -TEXT_ACTION_LIFT)
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
    Column(modifier = Modifier.fillMaxSize()) {
        AssetTopBar(name = name, watched = watched, onWatch = onWatch, onBack = onBack, onLock = onLock)
        // A lazy list, so the sections below the fold are composed when they scroll in, not on the page's first frame.
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = ClearAirSpacing.md)) {
            item {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(HEAD_TOP))
                    ClearAirDisc(size = HEAD_DISC) { icon(HEAD_LOGO) }
                    Spacer(Modifier.height(EYEBROW_GAP))
                    ClearAirEyebrow(text = "$name · $symbol", top = 0.dp, bottom = 0.dp, modifier = Modifier.cssLine(EYEBROW_LINE))
                    Spacer(Modifier.height(PRICE_GAP))
                    Box(modifier = Modifier.fillMaxWidth().height(PRICE_BLOCK), contentAlignment = Alignment.TopCenter) {
                        ClearAirAmount(text = priceText ?: "—", style = ClearAirType.hero, signGap = SIGN_GAP, modifier = Modifier.offset(y = PRICE_SHIFT))
                    }
                    change?.let { value ->
                        Text(
                            text = value.text,
                            style = ClearAirChangeLineStyle.copy(lineHeight = CHANGE_LINE),
                            color = if (value.rising) palette.vaultBlue else palette.rose,
                            modifier = Modifier.cssLine(CHANGE_LINE_DP),
                        )
                    }
                }
                Spacer(Modifier.height(CHART_GAP))
                TickChart(
                    series = series ?: PriceSeries(emptyList()),
                    modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter).testTag(CHART_TAG),
                )
                RangeRow(selected = range, onSelect = onRange)
                if (!priceOnly) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = ACTIONS_TOP)
                            .padding(horizontal = ClearAirSpacing.gutter, vertical = ACTIONS_PAD),
                        horizontalArrangement = Arrangement.spacedBy(ACTIONS_GAP, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (held == null || senders.isEmpty()) {
                            ClearAirPrimaryButton(text = "Receive", onClick = { open(CoinPicker.RECEIVE) }, icon = ClearAirGlyph.RECEIVE, fullWidth = false)
                            if (senders.isNotEmpty()) {
                                ClearAirTextAction(text = "Send $symbol", onClick = { open(CoinPicker.SEND) }, icon = ClearAirGlyph.SEND, modifier = textActionModifier)
                            }
                        } else {
                            ClearAirPrimaryButton(text = "Send $symbol", onClick = { open(CoinPicker.SEND) }, icon = ClearAirGlyph.SEND, fullWidth = false)
                            ClearAirTextAction(text = "Receive", onClick = { open(CoinPicker.RECEIVE) }, icon = ClearAirGlyph.RECEIVE, modifier = textActionModifier)
                        }
                    }
                }
            }
            if (priceOnly) return@LazyColumn
            item {
                held?.let { coin ->
                    ClearAirEyebrow(
                        text = "Your $symbol",
                        top = 0.dp,
                        bottom = 0.dp,
                        modifier = Modifier.padding(start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter, top = SECTION_TOP, bottom = ClearAirSpacing.xxs).cssLine(EYEBROW_LINE),
                    )
                    HeldRow(coin = coin, icon = icon, onClick = onHoldings)
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter, top = SECTION_TOP, bottom = ClearAirSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ClearAirEyebrow(text = "Activity", top = 0.dp, bottom = 0.dp, modifier = Modifier.cssLine(EYEBROW_LINE))
                    Text(
                        text = "All",
                        style = ALL_STYLE,
                        color = palette.ink,
                        modifier = Modifier.cssLine(ALL_LINE).clickable(role = Role.Button, onClick = onAllActivity),
                    )
                }
            }
            item { activity() }
        }
    }
    if (sheetsComposed) {
        ClearAirSheet(visible = picker == CoinPicker.SEND, onClose = { picker = null }) {
            ClearAirChoiceSheet(
                title = "Send $symbol from",
                groups = listOf(
                    ChoiceGroup(
                        null,
                        senders.map { account ->
                            ChoiceRow(
                                title = account.title,
                                detail = account.detail,
                                trailing = account.trailing,
                                onPick = { picker = null; account.onSend?.invoke() },
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
        modifier = Modifier.fillMaxWidth().height(TOP_BAR_HEIGHT).padding(horizontal = TOP_BAR_SIDE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClearAirIconButton(glyph = ClearAirGlyph.BACK, label = "Back", onClick = onBack)
        Text(
            text = name,
            modifier = Modifier.weight(1f).padding(start = TITLE_START),
            style = ClearAirType.topBar,
            color = palette.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ClearAirIconButton(
            glyph = ClearAirGlyph.STAR,
            label = if (watched) "Remove $name from the watchlist" else "Add $name to the watchlist",
            onClick = onWatch,
            filled = watched,
            role = Role.Checkbox,
        )
        ClearAirIconButton(glyph = ClearAirGlyph.LOCK, label = "Lock", onClick = onLock)
    }
}

/** The coin's holdings row, which opens Holdings Breakdown. Its value sits on the right when a price is read, and its chains' logos follow the count. */
@Composable
private fun HeldRow(coin: CoinHeld, icon: @Composable (Dp) -> Unit, onClick: () -> Unit) {
    val palette = LocalClearAirPalette.current
    val value = coin.value
    val chains: (@Composable () -> Unit)? = if (coin.chainIcons.isEmpty()) null else { { ChainDots(coin.chainIcons) } }
    val trailing: (@Composable () -> Unit)? = if (value == null) null else { { Text(text = value, style = ClearAirType.rowValue, color = palette.ink) } }
    ClearAirRow(
        title = coin.amount,
        titleStyle = HELD_TITLE,
        description = coin.summary,
        descriptionSuffix = chains,
        leading = { ClearAirDisc { icon(ROW_LOGO) } },
        trailing = trailing,
        onClick = onClick,
    )
}

/**
 * The chart time frames as mono text, spread across the page with 18 dp at each side. The chosen one is bold with the spark's diamond under it, and
 * tapping one chooses it.
 */
@Composable
private fun RangeRow(selected: ChartFrame, onSelect: (ChartFrame) -> Unit) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = RANGE_SIDE, end = RANGE_SIDE, top = RANGE_TOP, bottom = RANGE_BOTTOM),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ChartFrame.entries.forEach { range ->
            val chosen = range == selected
            Box(
                modifier = Modifier
                    .clickable(role = Role.Tab, onClick = { onSelect(range) })
                    .semantics { this.selected = chosen },
            ) {
                Text(
                    text = range.label,
                    modifier = Modifier.padding(horizontal = RANGE_ITEM_X, vertical = RANGE_ITEM_Y).cssLine(RANGE_LINE),
                    style = RANGE_STYLE.copy(fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium),
                    color = if (chosen) palette.ink else palette.ink2,
                )
                // Every range keeps the diamond's place, so the row does not move when the choice does.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .size(RANGE_SPARK)
                        .rotate(45f)
                        .background(if (chosen) palette.spark else Color.Transparent),
                )
            }
        }
    }
}

// The page's measures, from the approved frame, in dp. Each is the frame's own CSS value; the top bar is the frame's 52 dp row.
private val TOP_BAR_HEIGHT = 52.dp
private val TOP_BAR_SIDE = 8.dp
private val TITLE_START = 6.dp
private val HEAD_TOP = 4.dp
private val HEAD_DISC = 48.dp
private val HEAD_LOGO = 28.dp
private val ROW_LOGO = 21.dp
private val EYEBROW_GAP = 10.dp
private val PRICE_GAP = 2.dp

/** The price's block, 66.8 dp and the 4 dp before the change line, and how far the price sits down in it, as the frame's raised currency sign sets it. */
private val PRICE_BLOCK = 70.8.dp
private val PRICE_SHIFT = 5.9.dp
/** The gap after the currency sign: the frame's 3 px margin plus what its lighter sign face leaves, measured against the frame's ink. */
private val SIGN_GAP = 5.dp
private val CHANGE_LINE = 17.sp
private val CHANGE_LINE_DP = 17.dp
private val EYEBROW_LINE = 14.dp
private val ALL_LINE = 21.dp
private val RANGE_LINE = 17.dp
private val CHART_GAP = 12.dp
private val RANGE_SIDE = 18.dp
private val RANGE_TOP = 4.dp
private val RANGE_BOTTOM = 2.dp
private val RANGE_ITEM_X = 5.dp
private val RANGE_ITEM_Y = 8.dp

/** The side of the spark under the chosen range: a square, turned into a diamond. */
private val RANGE_SPARK = 5.dp
private val RANGE_STYLE = ClearAirType.address.copy(fontSize = 12.5.sp, lineHeight = 17.sp, letterSpacing = 0.sp)
private val ACTIONS_TOP = 6.dp
private val ACTIONS_PAD = 6.dp
private val ACTIONS_GAP = 18.dp
private val TEXT_ACTION_PAD = 6.dp
private val TEXT_ACTION_LIFT = 1.dp
private val SECTION_TOP = 18.dp
private val ALL_STYLE = ClearAirType.rowTitle.copy(fontSize = 13.sp, lineHeight = 21.sp)
private val HELD_TITLE = ClearAirType.rowValue.copy(fontSize = 15.sp)

/** The test tag of the price chart. */
internal const val CHART_TAG = "asset-chart"

