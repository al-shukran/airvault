package app.lumio.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.watchlist.WatchedAsset
import app.lumio.watchlist.WatchlistSort

/** The space above Send and Receive, under the top bar, and the band they sit in. */
private val ACTIONS_TOP = 10.dp
private val ACTIONS_BAND = 6.dp

/** The width of the Send capsule, which hugs "Send", and the gap between it and Receive. */
private val SEND_WIDTH = 129.dp
private val ACTIONS_GAP = 18.dp

/** The 6 dp a text action keeps on each side of its words, which centres Send and Receive as the mock does. */
private val TEXT_ACTION_SIDE = 6.dp

/** The watchlist header: its space above and below, the height of its line, and the gap between its two actions. */
private val HEADER_TOP = 18.dp
private val HEADER_BOTTOM = 4.dp
private val HEADER_LINE = 21.dp
private val HEADER_GAP = 14.dp

/** The glyph of a header action. */
private val HEADER_GLYPH = 14.dp

/** The footnote's space above and below. */
private val FOOTNOTE_TOP = 14.dp
private val FOOTNOTE_BOTTOM = 32.dp

/** The empty state: the space above its mark, the mark, its glyph, the spark on it, and the spaces down to the actions. */
private val EMPTY_TOP = 56.dp
private val EMPTY_MARK = 76.dp
private val EMPTY_GLYPH = 30.dp
private val EMPTY_SPARK = 10.dp
private val EMPTY_SPARK_RING = 18.dp
private val EMPTY_TITLE_TOP = 18.dp
private val EMPTY_BODY_TOP = 8.dp
private val EMPTY_ACTIONS_TOP = 19.dp
private val EMPTY_ACTION = 45.dp

/** A header action's words: Archivo 114 SemiBold at 13 sp. */
private val headerActionStyle
    get() = ClearAirType.rowTitle.copy(fontSize = 13.sp, lineHeight = 18.sp)

/**
 * One watchlist row as Home shows it: the [asset], its full [name] and [ticker], its [icon], its price and 24 h change, and the [ticks] of its day's
 * prices, oldest first. A null [priceUsd] or [change24hPct] is shown as absent, never as zero, and fewer than two [ticks] draw no chart.
 */
data class WatchlistRow(
    val asset: WatchedAsset,
    val name: String,
    val ticker: String,
    val icon: AssetIconSpec,
    val priceUsd: String?,
    val change24hPct: Double?,
    val ticks: List<Double> = emptyList(),
)

/**
 * The Home tab's body: Send and Receive, then the app's watchlist with its sort control and its add action, and a dashed row that adds an asset at the
 * end. [rows] are already in the order the list shows them, and null until the watchlist is read, when only Send and Receive show. A test network shows no prices, because test coins have no value. [footnote] says where the
 * prices are from and when. An empty watchlist says so, with the add and [onRestore], which brings back the top coins. [logo] draws a row's disc.
 */
@Composable
fun HomeScreen(
    rows: List<WatchlistRow>?,
    sort: WatchlistSort,
    testnet: Boolean,
    footnote: String?,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onSort: () -> Unit,
    onAdd: () -> Unit,
    onRestore: () -> Unit,
    onOpen: (WatchedAsset) -> Unit,
    notice: String? = null,
    logo: @Composable (WatchlistRow) -> Unit = { AssetDisc(it.icon) },
) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ACTIONS_TOP).padding(vertical = ACTIONS_BAND),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ACTIONS_GAP, Alignment.CenterHorizontally),
    ) {
        ClearAirPrimaryButton(text = "Send", onClick = onSend, modifier = Modifier.width(SEND_WIDTH), icon = ClearAirGlyph.SEND, fullWidth = false, labelStyle = ClearAirType.button)
        ClearAirTextAction(text = "Receive", onClick = onReceive, icon = ClearAirGlyph.RECEIVE, modifier = Modifier.padding(horizontal = TEXT_ACTION_SIDE))
    }
    notice?.let { Text(text = it, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter), style = ClearAirType.body, color = palette.rose) }
    if (rows == null) return
    if (rows.isEmpty()) {
        EmptyWatchlist(onAdd = onAdd, onRestore = onRestore)
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter, top = HEADER_TOP, bottom = HEADER_BOTTOM)
            .height(HEADER_LINE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClearAirEyebrow(text = "Watchlist · ${rows.size}", top = 0.dp, bottom = 0.dp, modifier = Modifier.weight(1f))
        HeaderAction(text = sort.label, glyph = ClearAirGlyph.SORT, glyphFirst = false, onClick = onSort)
        Spacer(Modifier.width(HEADER_GAP))
        HeaderAction(text = "Add", glyph = ClearAirGlyph.PLUS, glyphFirst = true, onClick = onAdd)
    }
    val valueWidth = valueColumnWidth(rows.map { it.priceUsd }, rows.map { it.change24hPct })
    rows.forEachIndexed { index, row ->
        WatchlistAssetRow(
            name = row.name,
            ticker = row.ticker,
            price = row.priceUsd,
            change = row.change24hPct,
            ticks = row.ticks,
            logo = { logo(row) },
            onClick = { onOpen(row.asset) },
            divider = index > 0,
            prices = !testnet,
            valueWidth = valueWidth,
        )
    }
    AddToWatchlistRow(onClick = onAdd)
    footnote?.let {
        Text(
            text = it,
            style = ClearAirType.small.whole(),
            color = palette.ink2,
            modifier = Modifier.fillMaxWidth().padding(start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter, top = FOOTNOTE_TOP, bottom = FOOTNOTE_BOTTOM),
        )
    }
}

/**
 * A text action of the watchlist header: the words in ink with a 14 dp glyph, before them or after. Its line is 21 dp, as the header's is, and its touch
 * target reaches 48 dp over the space around it.
 */
@Composable
private fun HeaderAction(text: String, glyph: ClearAirGlyph, glyphFirst: Boolean, onClick: () -> Unit) {
    ClearAirTextAction(
        text = text,
        onClick = onClick,
        modifier = Modifier.requiredHeight(ClearAirSpacing.touchTarget),
        style = headerActionStyle,
        icon = glyph.takeIf { glyphFirst },
        iconSize = HEADER_GLYPH,
        trailingIcon = glyph.takeUnless { glyphFirst },
    )
}

/** The empty watchlist: the star mark with its spark, the title, what a watchlist is for, and the two ways to fill it. */
@Composable
private fun EmptyWatchlist(onAdd: () -> Unit, onRestore: () -> Unit) {
    val palette = LocalClearAirPalette.current
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.padding(top = EMPTY_TOP).size(EMPTY_MARK).background(palette.raised, CircleShape), contentAlignment = Alignment.Center) {
            ClearAirIcon(glyph = ClearAirGlyph.STAR, color = palette.ink, size = EMPTY_GLYPH)
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp).size(EMPTY_SPARK), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.requiredSize(EMPTY_SPARK_RING).rotate(45f).background(palette.background))
                Box(modifier = Modifier.requiredSize(EMPTY_SPARK).rotate(45f).background(palette.spark))
            }
        }
        Text(
            text = "Your Watchlist Is Empty",
            style = ClearAirType.titleXL.whole(),
            color = palette.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = EMPTY_TITLE_TOP, start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter),
        )
        Text(
            text = "Add any coin, token or fund to follow its price here. Removing one never touches your accounts.",
            style = ClearAirType.body.whole(),
            color = palette.ink2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = EMPTY_BODY_TOP, start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter),
        )
        Spacer(Modifier.height(EMPTY_ACTIONS_TOP))
        ClearAirTextAction(text = "Add to Watchlist", onClick = onAdd, icon = ClearAirGlyph.PLUS, modifier = Modifier.height(EMPTY_ACTION))
        ClearAirTextAction(text = "Restore Top Coins", onClick = onRestore, modifier = Modifier.height(EMPTY_ACTION))
    }
}
