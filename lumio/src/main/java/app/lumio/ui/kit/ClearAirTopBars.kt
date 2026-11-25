package app.lumio.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The height of a top bar's row, as the mock draws it, and the space at its sides. */
private val TOP_BAR_HEIGHT = 52.dp
private val TOP_BAR_SIDE = 8.dp

/** The box of a top bar's icon and the icon in it, as the mock draws them. */
private val ICON_BOX = 44.dp
private val ICON_SIZE = 22.dp

/** The space before the root title, and between it and the switcher's chevron, and the chevron's size. */
private val TITLE_START = 6.dp
private val TITLE_GAP = 6.dp
private val SWITCHER_CHEVRON = 16.dp

/** The top bar's title: 18 sp, bold, one step under a row title. */
private val topBarTitle
    get() = ClearAirType.titleL.copy(fontSize = 18.sp, lineHeight = 23.sp, letterSpacing = (-0.015).em)

/**
 * The root top bar of a tab: the wallet emblem, the tab's [title], the search and the lock. The emblem opens the profile panel, search opens the
 * search sheet, and the lock locks Lumio at once. Only Home's title is the wallet switcher, so only it ([switcher]) has the ▾ and opens the panel.
 */
@Composable
fun ClearAirRootTopBar(
    title: String,
    onPanel: () -> Unit,
    onSearch: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
    switcher: Boolean = false,
) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier.fillMaxWidth().height(TOP_BAR_HEIGHT).padding(horizontal = TOP_BAR_SIDE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(ICON_BOX).pressable(onClick = onPanel, label = "Open the profile panel"),
            contentAlignment = Alignment.Center,
        ) {
            ClearAirWalletEmblem(tile = 36.dp)
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .then(if (switcher) Modifier.pressable(onClick = onPanel, label = "Open the profile panel") else Modifier)
                .padding(start = TITLE_START),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = topBarTitle, color = palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (switcher) {
                Spacer(Modifier.width(TITLE_GAP))
                ClearAirIcon(glyph = ClearAirGlyph.DOWN, size = SWITCHER_CHEVRON, color = palette.ink2)
            }
        }
        ClearAirIconButton(glyph = ClearAirGlyph.SEARCH, label = "Search", onClick = onSearch)
        ClearAirIconButton(glyph = ClearAirGlyph.LOCK, label = "Lock", onClick = onLock)
    }
}

/**
 * The top bar of a page: a back arrow labelled "Back to [backLabel]", or just "Back" when [backLabel] is null, the page's [title], which wraps rather
 * than clips, and the lock. Back and the system back both return one page. A page that ends the flow rather than going back ([close]) shows a ×
 * labelled "Close" in place of the arrow.
 */
@Composable
fun ClearAirPageTopBar(
    title: String,
    backLabel: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
    close: Boolean = false,
) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = TOP_BAR_HEIGHT).padding(horizontal = TOP_BAR_SIDE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (close) {
            ClearAirIconButton(glyph = ClearAirGlyph.CLOSE, label = "Close", onClick = onBack)
        } else {
            ClearAirIconButton(glyph = ClearAirGlyph.BACK, label = backLabel?.let { "Back to $it" } ?: "Back", onClick = onBack)
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(start = TITLE_START),
            style = topBarTitle,
            color = palette.ink,
            textAlign = TextAlign.Start,
        )
        ClearAirIconButton(glyph = ClearAirGlyph.LOCK, label = "Lock", onClick = onLock)
    }
}

/** A 44 dp icon button in ink, the mock's box for a top bar's icon, labelled for TalkBack. */
@Composable
fun ClearAirIconButton(glyph: ClearAirGlyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Box(
        modifier = modifier.size(ICON_BOX).pressable(onClick = onClick, label = label),
        contentAlignment = Alignment.Center,
    ) {
        ClearAirIcon(glyph = glyph, color = palette.ink, size = ICON_SIZE)
    }
}

/** A press target with a button role and a spoken [label], which scales as a Clear Air press does. */
private fun Modifier.pressable(onClick: () -> Unit, label: String): Modifier =
    semantics {
        role = Role.Button
        contentDescription = label
    }.lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
