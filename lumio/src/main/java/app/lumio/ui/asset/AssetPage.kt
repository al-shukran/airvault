package app.lumio.ui.asset

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.price.ChartFrame
import app.lumio.data.price.PriceSeries
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.home.ChainDots
import app.lumio.ui.kit.ChoiceGroup
import app.lumio.ui.kit.ChoiceRow
import app.lumio.ui.kit.ClearAirChangeLineStyle
import app.lumio.ui.kit.ClearAirChoiceSheet
import app.lumio.ui.kit.ClearAirDisc
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirIconButton
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.kit.cssLine
import app.lumio.ui.kit.inLineBox
import app.lumio.ui.kit.spacedLikeCss
import app.lumio.ui.kit.splitAmount

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
    // The frame's text action has 6 dp at each side.
    val textActionModifier = Modifier.padding(horizontal = TEXT_ACTION_PAD)
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
                    Price(text = priceText ?: "—")
                    change?.let { value -> ChangeLine(change = value) }
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
                            ClearAirPrimaryButton(text = "Receive", onClick = { open(CoinPicker.RECEIVE) }, icon = ClearAirGlyph.RECEIVE, fullWidth = false, labelStyle = CAPSULE_STYLE)
                            if (senders.isNotEmpty()) {
                                ClearAirTextAction(text = "Send $symbol", onClick = { open(CoinPicker.SEND) }, icon = ClearAirGlyph.SEND, modifier = textActionModifier, style = TEXT_ACTION_STYLE)
                            }
                        } else {
                            ClearAirPrimaryButton(text = "Send $symbol", onClick = { open(CoinPicker.SEND) }, icon = ClearAirGlyph.SEND, fullWidth = false, labelStyle = CAPSULE_STYLE)
                            ClearAirTextAction(text = "Receive", onClick = { open(CoinPicker.RECEIVE) }, icon = ClearAirGlyph.RECEIVE, modifier = textActionModifier, style = TEXT_ACTION_STYLE)
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
                        modifier = Modifier.clickable(role = Role.Button, onClick = onAllActivity),
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

/** The price as the frame sets it: the sign, then the whole figures, then the dimmed fraction, each its own Text with the frame's box. */
@Composable
private fun Price(text: String) {
    val palette = LocalClearAirPalette.current
    val parts = splitAmount(text)
    val figures = ClearAirType.hero.copy(lineHeight = FIGURES_LINE).inLineBox()
    Box(modifier = Modifier.fillMaxWidth().height(PRICE_BLOCK), contentAlignment = Alignment.TopCenter) {
        Row {
            if (parts.prefix.isNotEmpty()) {
                Text(text = parts.prefix, style = SIGN_STYLE, color = palette.ink, modifier = Modifier.offset(y = SIGN_TOP))
                Spacer(Modifier.width(SIGN_GAP))
            }
            Text(text = parts.whole, style = figures, color = palette.ink, modifier = Modifier.offset(y = FIGURES_TOP).spacedLikeCss(FIGURES_SPACING))
            Text(text = parts.fraction + parts.suffix, style = figures, color = palette.ink2, modifier = Modifier.offset(y = FIGURES_TOP).spacedLikeCss(FIGURES_SPACING))
        }
    }
}

/**
 * The change line. Its rise or fall arrow is drawn at the frame's size and place: the font's own glyph is another size on each device, so the text keeps
 * the arrow, for the words and the width, and is not painted there.
 */
@Composable
private fun ChangeLine(change: PriceChange) {
    val palette = LocalClearAirPalette.current
    val color = if (change.rising) palette.vaultBlue else palette.rose
    val arrow = change.text.firstOrNull()?.takeIf { it == RISE || it == FALL }
    val text = remember(change.text) {
        buildAnnotatedString {
            if (arrow != null) withStyle(SpanStyle(color = Color.Transparent)) { append(arrow) }
            append(change.text.drop(if (arrow != null) 1 else 0))
        }
    }
    Box {
        Text(text = text, style = CHANGE_STYLE, color = color)
        if (arrow != null) {
            Canvas(modifier = Modifier.offset(x = ARROW_LEFT, y = ARROW_TOP).size(ARROW_WIDTH, ARROW_HEIGHT)) {
                val path = Path().apply {
                    if (arrow == FALL) {
                        moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height)
                    } else {
                        moveTo(0f, size.height); lineTo(size.width, size.height); lineTo(size.width / 2, 0f)
                    }
                    close()
                }
                drawPath(path = path, color = color)
            }
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

/**
 * The coin's holdings row, which opens Holdings Breakdown, as the approved frame sets it: a 36 dp disc, the amount in the mono face over the summary, its
 * chains' logos after the summary, the value in dollars when a price is read, and a chevron. The row is 62 dp at least.
 */
@Composable
private fun HeldRow(coin: CoinHeld, icon: @Composable (Dp) -> Unit, onClick: () -> Unit) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ROW_HEIGHT)
            .lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClearAirDisc { icon(ROW_LOGO) }
        Spacer(Modifier.width(ClearAirSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = coin.amount, style = HELD_TITLE, color = palette.ink, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = coin.summary,
                    style = HELD_CAPTION,
                    color = palette.ink2,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (coin.chainIcons.isNotEmpty()) {
                    Spacer(Modifier.width(CHAINS_GAP))
                    ChainDots(coin.chainIcons)
                }
            }
        }
        coin.value?.let { value ->
            Spacer(Modifier.width(ClearAirSpacing.sm))
            Text(text = value, style = HELD_VALUE, color = palette.ink)
        }
        Spacer(Modifier.width(ClearAirSpacing.sm))
        val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
        ClearAirIcon(glyph = ClearAirGlyph.CHEVRON, color = palette.ink3, size = CHEVRON, modifier = Modifier.graphicsLayer { scaleX = if (mirrored) -1f else 1f })
    }
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
                    modifier = Modifier.padding(horizontal = RANGE_ITEM_X, vertical = RANGE_ITEM_Y),
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

/** The price's block: its 56 dp line, and the 4 dp before the change line, plus the 10.8 dp that the figures' 66 dp box stands above it. */
private val PRICE_BLOCK = 70.8.dp
private val FIGURES_TOP = 5.84.dp
private val FIGURES_LINE = 66.sp

/** The figures' letter spacing, -0.06 em of 50 sp. A browser sets it after the last figure too, which Compose does not. */
private val FIGURES_SPACING = (-3).dp

/** The currency sign: Archivo Regular at 23 sp in a 25 dp box that starts 15 dp into the block, then 3 dp before the figures. */
private val SIGN_STYLE = ClearAirType.body.copy(fontSize = 23.sp, lineHeight = 25.sp, letterSpacing = 0.sp).inLineBox()
private val SIGN_TOP = 15.dp
private val SIGN_GAP = 3.dp
private val CHANGE_STYLE = ClearAirChangeLineStyle.copy(lineHeight = 17.sp).inLineBox()
private val EYEBROW_LINE = 14.dp
private val CHART_GAP = 12.dp
private val RANGE_SIDE = 18.dp
private val RANGE_TOP = 4.dp
private val RANGE_BOTTOM = 2.dp
private val RANGE_ITEM_X = 5.dp
private val RANGE_ITEM_Y = 8.dp

/** The side of the spark under the chosen range: a square, turned into a diamond. */
private val RANGE_SPARK = 5.dp
private val RANGE_STYLE = ClearAirType.address.copy(fontSize = 12.5.sp, lineHeight = 17.sp, letterSpacing = 0.sp).inLineBox()
private val ACTIONS_TOP = 6.dp
private val ACTIONS_PAD = 6.dp
private val ACTIONS_GAP = 18.dp
private val TEXT_ACTION_PAD = 6.dp
private val SECTION_TOP = 18.dp
private val ALL_STYLE = ClearAirType.rowTitle.copy(fontSize = 13.sp, lineHeight = 21.sp).inLineBox()
private val CAPSULE_STYLE = ClearAirType.button.copy(lineHeight = 21.sp).inLineBox()
private val TEXT_ACTION_STYLE = ClearAirType.rowTitle.copy(lineHeight = 21.sp).inLineBox()
private val HELD_TITLE = ClearAirType.rowValue.copy(fontSize = 15.sp).inLineBox()
private val HELD_VALUE = ClearAirType.rowValue.inLineBox()
private val HELD_CAPTION = ClearAirType.caption.inLineBox()
private val ROW_HEIGHT = 62.dp
private val CHAINS_GAP = 2.dp
private val CHEVRON = 16.dp

private const val RISE = '\u25B2'
private const val FALL = '\u25BC'

/** The arrow's triangle in the frame: 8.8 dp wide and 9 dp high, 3.8 dp under the line's top and 0.3 dp before the text. */
private val ARROW_WIDTH = 8.8.dp
private val ARROW_HEIGHT = 9.dp
private val ARROW_LEFT = (-0.3).dp
private val ARROW_TOP = 3.8.dp

/** The test tag of the price chart. */
internal const val CHART_TAG = "asset-chart"

