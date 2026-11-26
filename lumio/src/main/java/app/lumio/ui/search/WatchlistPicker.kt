package app.lumio.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.home.AssetDisc
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirPageTopBar
import app.lumio.watchlist.WatchedAsset
import java.text.NumberFormat
import java.util.Locale

/** An asset's rank by market cap: a place on the source's list, or [Unranked] when the source lists the asset with no rank. */
sealed interface MarketRank {
    data class Place(val place: Int) : MarketRank

    data object Unranked : MarketRank
}

/**
 * One asset the picker offers: its [name] and [ticker], its [icon], its [rank] (null when no source ranks it), how many other tokens share its name
 * ([sameName]), and whether it is on the watchlist ([watched]).
 */
data class PickerRow(
    val asset: WatchedAsset,
    val name: String,
    val ticker: String,
    val icon: AssetIconSpec,
    val watched: Boolean,
    val rank: MarketRank? = null,
    val sameName: Int = 0,
)

/** The rank after a name, as "#4,004", or null when the asset has no place on the list. */
fun rankText(rank: MarketRank?): String? =
    (rank as? MarketRank.Place)?.let { "#" + NumberFormat.getIntegerInstance(Locale.US).format(it.place) }

/**
 * The line under a name: the ticker, left out when it reads the same as the name, then how many other tokens share the name, then "Unranked" when the
 * source lists the asset with no rank. Null when nothing is left, so the row keeps the one-line height.
 */
fun pickerDetail(row: PickerRow): String? = listOfNotNull(
    row.ticker.takeUnless { it.isBlank() || it.equals(row.name, ignoreCase = true) },
    when (row.sameName) {
        0 -> null
        1 -> "1 more token shares this name"
        else -> "${row.sameName} more tokens share this name"
    },
    "Unranked".takeIf { row.rank == MarketRank.Unranked },
).joinToString(" · ").ifEmpty { null }

/** The disc that leads a row. A divider starts after it, where the row's text does. */
private val DISC = 36.dp

/** The row's smallest height, its second line's style and the rank's mono style, as the kit's row draws them. */
private val ROW_HEIGHT = 62.dp
private val DETAIL = ClearAirType.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp)
private val STAR_BOX = 44.dp
private val STAR = 22.dp

/**
 * Add to Watchlist: a plain page with the search field, then [rows] under [label]. The star of each row adds the asset to the watchlist or takes it
 * off ([onToggle]); a filled star is on the list.
 */
@Composable
fun WatchlistPicker(
    query: String,
    onQuery: (String) -> Unit,
    label: String,
    rows: List<PickerRow>,
    onToggle: (WatchedAsset) -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ClearAirPageTopBar(title = "Add to Watchlist", backLabel = "Home", onBack = onBack, onLock = onLock)
        SearchField(query = query, onQuery = onQuery, placeholder = "Name or ticker")
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item(key = "label") {
                ClearAirEyebrow(text = label, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
            }
            itemsIndexed(rows, key = { _, row -> row.asset.toString() }) { index, row ->
                PickerRowItem(row = row, divider = index > 0, onToggle = { onToggle(row.asset) })
            }
        }
    }
}

/** One row: the logo disc, the name with its rank, the line under it, and the star. A long name wraps, and the rank follows its last word. */
@Composable
private fun PickerRowItem(row: PickerRow, divider: Boolean, onToggle: () -> Unit) {
    val palette = LocalClearAirPalette.current
    val inset = ClearAirSpacing.gutter + DISC + ClearAirSpacing.sm
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (divider) {
                    drawLine(palette.hairline, Offset(inset.toPx(), 0f), Offset(size.width - ClearAirSpacing.gutter.toPx(), 0f), 1.dp.toPx())
                }
            }
            .defaultMinSize(minHeight = ROW_HEIGHT)
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssetDisc(spec = row.icon)
        Spacer(Modifier.width(ClearAirSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            val rank = rankText(row.rank)
            val title = buildAnnotatedString {
                append(row.name)
                if (rank != null) {
                    // A no-break space keeps the rank with the name's last word, so a wrap takes the two down together.
                    append(" ")
                    withStyle(SpanStyle(fontFamily = ClearAirType.address.fontFamily, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = palette.ink2)) {
                        append(rank)
                    }
                }
            }
            Text(text = title, style = ClearAirType.rowTitle, color = palette.ink)
            pickerDetail(row)?.let { Text(text = it, style = DETAIL, color = palette.ink2) }
        }
        Spacer(Modifier.width(ClearAirSpacing.sm))
        Box(
            modifier = Modifier
                .size(STAR_BOX)
                .semantics { contentDescription = if (row.watched) "On your watchlist" else "Add to watchlist" }
                .toggleable(value = row.watched, role = Role.Checkbox, onValueChange = { onToggle() }),
            contentAlignment = Alignment.Center,
        ) {
            ClearAirIcon(glyph = ClearAirGlyph.STAR, color = palette.ink, filled = row.watched, size = STAR)
        }
    }
}
