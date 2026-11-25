package app.lumio.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirRow

/** The tabs of the search, in their order. Each shows the results of its own kind. */
enum class SearchTab(val label: String) {
    MAIN("Main"),
    ADDRESS_BOOK("Address Book"),
    WATCHLIST("Watchlist"),
    TRENDING("Trending"),
    ETH("ETH"),
    SOL("SOL"),
    CHAINS("Chains"),
    PROVIDERS("Providers"),
}

/** One result: its [key], the [title] and [detail] the row shows, and the [onOpen] that opens its page, or null for a result with no page yet. */
data class SearchRow(val key: String, val title: String, val detail: String, val onOpen: (() -> Unit)?)

/** A group of results under its [label]. A null label is a group with nothing above it, such as the address row. */
data class SearchSection(val label: String?, val rows: List<SearchRow>)

/** The height of the search field: a capsule-like field, 48 dp tall, with the proposal's 24 dp corners. It has no close button: the sheet closes with its scrim and Back. */
private val FIELD_HEIGHT = 48.dp
private val FIELD_CORNER = 24.dp

/** A horizontal rule along the bottom edge of the drawing area, [width] thick. */
private fun DrawScope.rule(color: Color, width: Float) {
    val y = size.height - width / 2f
    drawLine(color = color, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = width)
}

/** Fades the last fifth of the row to nothing, as the mock's tab fade does. */
private fun Modifier.fadeEnd(): Modifier = graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
    drawContent()
    drawRect(Brush.horizontalGradient(0.8f to Color.Black, 1f to Color.Transparent, startX = 0f, endX = size.width), blendMode = BlendMode.DstIn)
}

/** The count of tabs that lie past the right edge of the tab row, which the "more" label names. */
private fun hiddenTabs(tabRow: LazyListState): Int {
    val info = tabRow.layoutInfo
    val lastWhole = info.visibleItemsInfo.lastOrNull { it.offset + it.size <= info.viewportEndOffset }?.index ?: return 0
    return SearchTab.entries.size - lastWhole - 1
}

/**
 * The search sheet's content. The field takes the query as the owner types it, the tabs choose which kind of result is shown, and [sections] are the
 * results of the tab. A query with no result says so in the sheet's own words. The root draws the sheet, so this
 * is only its content.
 */
@Composable
fun SearchSheet(
    query: String,
    onQuery: (String) -> Unit,
    tab: SearchTab,
    onTab: (SearchTab) -> Unit,
    sections: List<SearchSection>,
) {
    val palette = LocalClearAirPalette.current
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .padding(start = ClearAirSpacing.md, top = 6.dp, end = ClearAirSpacing.md)
                .fillMaxWidth()
                .height(FIELD_HEIGHT)
                .clip(RoundedCornerShape(FIELD_CORNER))
                .background(palette.raised)
                .padding(horizontal = ClearAirSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClearAirIcon(glyph = ClearAirGlyph.SEARCH, color = palette.ink2, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(text = "Search anything across your wallet", style = ClearAirType.body, color = palette.ink2, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = ClearAirType.body.copy(color = palette.ink),
                    cursorBrush = SolidColor(palette.spark),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val tabRow = rememberLazyListState()
        val hidden = hiddenTabs(tabRow)
        val more = tabRow.canScrollForward
        Row(
            modifier = Modifier
                .padding(top = ClearAirSpacing.xs)
                .fillMaxWidth()
                .drawBehind { rule(color = palette.hairline, width = 1.dp.toPx()) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                state = tabRow,
                // A tab that the row cuts off at its right edge fades out, so the cut reads as "more to swipe" and not as a broken label.
                modifier = Modifier.weight(1f).then(if (more) Modifier.fadeEnd() else Modifier),
                contentPadding = PaddingValues(horizontal = ClearAirSpacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(ClearAirSpacing.lg),
                flingBehavior = rememberSnapFlingBehavior(lazyListState = tabRow),
            ) {
                items(SearchTab.entries) { option ->
                    val active = option == tab
                    Text(
                        text = option.label,
                        style = ClearAirType.rowTitle,
                        color = if (active) palette.ink else palette.ink2,
                        maxLines = 1,
                        modifier = Modifier
                            .selectable(selected = active, role = Role.Tab, onClick = { onTab(option) })
                            .drawBehind { if (active) rule(color = palette.ink, width = 2.dp.toPx()) }
                            .padding(top = ClearAirSpacing.sm, bottom = 10.dp),
                    )
                }
            }
            if (hidden > 0) {
                Row(
                    modifier = Modifier.padding(start = ClearAirSpacing.xs, end = ClearAirSpacing.gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "$hidden more", style = ClearAirType.small, color = palette.ink2, maxLines = 1)
                    ClearAirIcon(glyph = ClearAirGlyph.CHEVRON, color = palette.ink2, size = 15.dp)
                }
            }
        }
        if (sections.isEmpty()) {
            Text(
                text = if (query.isBlank()) "Try an address, a ticker or a chain." else "No match for “${query.trim()}”. Try an address, a ticker or a chain.",
                style = ClearAirType.body,
                color = palette.ink2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                sections.forEach { section ->
                    section.label?.let { label ->
                        item(key = "label-$label") {
                            ClearAirEyebrow(text = label, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs))
                        }
                    }
                    items(section.rows, key = { it.key }) { row ->
                        ClearAirRow(title = row.title, description = row.detail, onClick = row.onOpen)
                    }
                }
            }
        }
    }
}
