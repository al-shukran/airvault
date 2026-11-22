package app.lumio.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirIconButton
import app.lumio.ui.kit.ClearAirRow

/** The tabs of the search, in their order. Each shows the results of its own kind; the tabs the wallet has no data for yet are not listed. */
enum class SearchTab(val label: String) {
    MAIN("Main"),
    WATCHLIST("Watchlist"),
    TRENDING("Trending"),
    CHAINS("Chains"),
}

/** One result: its [key], the [title] and [detail] the row shows, and the [onOpen] that opens its page, or null for a result with no page yet. */
data class SearchRow(val key: String, val title: String, val detail: String, val onOpen: (() -> Unit)?)

/** A group of results under its [label]. A null label is a group with nothing above it, such as the address row. */
data class SearchSection(val label: String?, val rows: List<SearchRow>)

/** The height of the search field: a capsule-like field, 48 dp tall, with the proposal's 24 dp corners. */
private val FIELD_HEIGHT = 48.dp
private val FIELD_CORNER = 24.dp

/**
 * The search sheet's content. The field takes the query as the owner types it, the tabs choose which kind of result is shown, and [sections] are the
 * results of the tab. A query with no result says so in the sheet's own words. [onClose] is the sheet's close action. The root draws the sheet, so this
 * is only its content.
 */
@Composable
fun SearchSheet(
    query: String,
    onQuery: (String) -> Unit,
    tab: SearchTab,
    onTab: (SearchTab) -> Unit,
    sections: List<SearchSection>,
    onClose: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    Column(modifier = Modifier.fillMaxSize().padding(top = ClearAirSpacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(FIELD_HEIGHT)
                    .clip(RoundedCornerShape(FIELD_CORNER))
                    .background(palette.raised)
                    .padding(horizontal = ClearAirSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClearAirIcon(glyph = ClearAirGlyph.SEARCH, color = palette.ink2)
                Spacer(Modifier.width(ClearAirSpacing.xs))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(text = "Search anything across your wallet", style = ClearAirType.body, color = palette.ink2, maxLines = 1)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = ClearAirType.body.copy(color = palette.ink),
                        cursorBrush = SolidColor(palette.ink),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            ClearAirIconButton(glyph = ClearAirGlyph.CLOSE, label = "Close", onClick = onClose)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(ClearAirSpacing.md),
        ) {
            SearchTab.entries.forEach { option ->
                val active = option == tab
                Text(
                    text = option.label,
                    style = if (active) ClearAirType.rowTitle else ClearAirType.label,
                    color = if (active) palette.ink else palette.ink2,
                    maxLines = 1,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).clickable { onTab(option) }.padding(vertical = ClearAirSpacing.xs),
                )
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
