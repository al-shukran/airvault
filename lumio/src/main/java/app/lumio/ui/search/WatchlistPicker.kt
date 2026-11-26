package app.lumio.ui.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.home.AssetIcon
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.kit.ClearAirDisc
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

/** The logo of a row: a [Mark] that Lumio ships, or the [Remote] image the market source names, which [WatchlistPicker] loads by its URL. */
sealed interface PickerLogo {
    data class Mark(val spec: AssetIconSpec) : PickerLogo

    /** An image at [url], or an empty disc while it loads or when the source gives none ([url] null). */
    data class Remote(val url: String?) : PickerLogo
}

/**
 * One asset the picker offers: its [name] and [ticker], its [logo], its [rank] (null when no market row was read for it), how many other tokens share
 * its name ([sameName]), and whether it is on the watchlist ([watched]).
 */
data class PickerRow(
    val asset: WatchedAsset,
    val name: String,
    val ticker: String,
    val logo: PickerLogo,
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
// The lines keep their whole height, as the mock's do, so the ticker sits a full line under the name.
private val UNTRIMMED = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)
// The mock's browser sets the line under a name about 0.25 dp a letter wider than Compose does, measured on its PNGs, so that line carries the
// spacing. A name keeps the face's own spacing, so it wraps where the mock's does on a phone.
private val TITLE = ClearAirType.rowTitle.copy(lineHeightStyle = UNTRIMMED)
private val DETAIL = ClearAirType.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp, lineHeightStyle = UNTRIMMED, letterSpacing = 0.02.em)

/**
 * The mock's browser places each line's ink higher in its line than Compose does: 0.9 dp for a name and the section label, 1.4 dp for the line under a
 * name, measured on its PNGs. The text is drawn that much higher, and the layout does not move.
 */
private val NAME_LIFT = 0.9.dp
private val DETAIL_LIFT = 1.4.dp
private val LOGO = 24.dp
private val LABEL = ClearAirType.eyebrow.copy(lineHeightStyle = UNTRIMMED)

/** The mock sets the rank in its own mono line box, which reaches 1 dp lower than the name's; a name line that ends in a rank is that much taller. */
private val RANK_LINE_EXTRA = 1.dp
private val STAR_BOX = 44.dp
private val STAR = 22.dp

/**
 * Add to Watchlist: a plain page with the search field, then [rows] under [label]. The star of each row adds the asset to the watchlist or takes it
 * off ([onToggle]); a filled star is on the list. [image] gives the bitmap of a [PickerLogo.Remote] logo, or null while it loads. Rows that are still
 * loading ([loading]) show as empty rows of the same height.
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
    image: @Composable (String) -> ImageBitmap?,
    loading: Boolean = false,
    notice: String? = null,
) {
    val palette = LocalClearAirPalette.current
    Column(modifier = Modifier.fillMaxSize()) {
        ClearAirPageTopBar(title = "Add to Watchlist", backLabel = "Home", onBack = onBack, onLock = onLock)
        SearchField(query = query, onQuery = onQuery, placeholder = "Name or ticker")
        notice?.let { Text(text = it, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter), style = ClearAirType.body, color = palette.rose) }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item(key = "label") {
                Text(
                    text = label.uppercase(),
                    style = LABEL,
                    color = palette.ink2,
                    modifier = Modifier
                        .padding(start = ClearAirSpacing.gutter, top = 18.dp, end = ClearAirSpacing.gutter, bottom = ClearAirSpacing.xxs)
                        .offset(y = -NAME_LIFT),
                )
            }
            if (loading) {
                items(LOADING_ROWS) { index -> LoadingRow(divider = index > 0) }
            }
            itemsIndexed(rows, key = { _, row -> row.asset.toString() }) { index, row ->
                PickerRowItem(row = row, divider = index > 0, image = image, onToggle = { onToggle(row.asset) })
            }
        }
    }
}

/** The test tag of each asset row. */
const val PICKER_ROW_TAG = "watchlist-picker-row"

/** The rows a first load shows while the list is read: as many as fill the screen. */
private const val LOADING_ROWS = 10

/** The frame of a row: its height, the gutters, and the divider on its top edge, which starts where the text does. */
@Composable
private fun RowFrame(divider: Boolean, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val palette = LocalClearAirPalette.current
    val inset = ClearAirSpacing.gutter + DISC + ClearAirSpacing.sm
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                if (divider) {
                    drawLine(palette.hairline, Offset(inset.toPx(), 0f), Offset(size.width - ClearAirSpacing.gutter.toPx(), 0f), 1.dp.toPx())
                }
            }
            .defaultMinSize(minHeight = ROW_HEIGHT)
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A row of a first load: an empty disc and two bars on the sunk tone, as the kit's loading rows are drawn. */
@Composable
private fun LoadingRow(divider: Boolean) {
    val palette = LocalClearAirPalette.current
    RowFrame(divider = divider) {
        ClearAirDisc {}
        Spacer(Modifier.width(ClearAirSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Box(Modifier.width(120.dp).height(11.dp).clip(RoundedCornerShape(6.dp)).background(palette.raised))
            Spacer(Modifier.height(ClearAirSpacing.xs))
            Box(Modifier.width(48.dp).height(11.dp).clip(RoundedCornerShape(6.dp)).background(palette.raised))
        }
    }
}

/** One row: the logo disc, the name with its rank, the line under it, and the star. A long name wraps, and the rank follows its last word. */
@Composable
private fun PickerRowItem(row: PickerRow, divider: Boolean, image: @Composable (String) -> ImageBitmap?, onToggle: () -> Unit) {
    val palette = LocalClearAirPalette.current
    RowFrame(divider = divider, modifier = Modifier.testTag(PICKER_ROW_TAG)) {
        ClearAirDisc {
            when (val logo = row.logo) {
                is PickerLogo.Mark -> AssetIcon(spec = logo.spec, size = LOGO)
                is PickerLogo.Remote -> logo.url?.let { image(it) }?.let { bitmap ->
                    Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(LOGO).clip(CircleShape))
                }
            }
        }
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
            Text(
                text = title,
                style = TITLE,
                color = palette.ink,
                modifier = Modifier.offset(y = -NAME_LIFT).then(if (rank != null) Modifier.padding(bottom = RANK_LINE_EXTRA) else Modifier),
            )
            pickerDetail(row)?.let { Text(text = it, style = DETAIL, color = palette.ink2, modifier = Modifier.offset(y = -DETAIL_LIFT)) }
        }
        Spacer(Modifier.width(ClearAirSpacing.sm))
        Box(
            modifier = Modifier
                .size(STAR_BOX)
                .semantics { contentDescription = if (row.watched) "On your watchlist" else "Add to watchlist" }
                .toggleable(value = row.watched, role = Role.Checkbox, onValueChange = { onToggle() }),
            contentAlignment = Alignment.Center,
        ) {
            // The mock's filled star keeps its outline, so it is drawn over the outline at the same size.
            ClearAirIcon(glyph = ClearAirGlyph.STAR, color = palette.ink, size = STAR)
            if (row.watched) ClearAirIcon(glyph = ClearAirGlyph.STAR, color = palette.ink, filled = true, size = STAR)
        }
    }
}
