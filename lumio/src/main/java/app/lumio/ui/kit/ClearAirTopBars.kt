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
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The height of a top bar's row, as the mock draws it: 52 dp, with 6 dp either side so the 48 dp touch targets centre their icons where the mock does. */
private val TOP_BAR_HEIGHT = 52.dp
private val TOP_BAR_SIDE = 6.dp

/** The gap between a top bar's icon and its title. */
private val TOP_BAR_TITLE_GAP = 4.dp

/** The top bar's title: 18 sp, bold, with the mock's tracking. */
private val topBarTitle
    get() = ClearAirType.topBar

/**
 * The root top bar of a tab: the wallet emblem, the tab's [title] with its ▾, the search and the lock. The emblem and the title open the profile
 * panel, search opens the search sheet, and the lock locks Lumio at once.
 */
@Composable
fun ClearAirRootTopBar(title: String, onPanel: () -> Unit, onSearch: () -> Unit, onLock: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier.fillMaxWidth().height(TOP_BAR_HEIGHT).padding(horizontal = TOP_BAR_SIDE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(ClearAirSpacing.touchTarget).pressable(onClick = onPanel, label = "Open the profile panel"),
            contentAlignment = Alignment.Center,
        ) {
            ClearAirWalletEmblem(tile = 36.dp)
        }
        Row(
            modifier = Modifier.weight(1f).pressable(onClick = onPanel, label = "Open the profile panel").padding(start = TOP_BAR_TITLE_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = topBarTitle, color = palette.ink)
            Spacer(Modifier.width(6.dp))
            ClearAirIcon(glyph = ClearAirGlyph.DOWN, color = palette.ink2, size = 16.dp)
        }
        ClearAirIconButton(glyph = ClearAirGlyph.SEARCH, label = "Search", onClick = onSearch)
        ClearAirIconButton(glyph = ClearAirGlyph.LOCK, label = "Lock", onClick = onLock)
    }
}

/**
 * The top bar of a page: a back arrow labelled "Back to [backLabel]", or just "Back" when [backLabel] is null, the page's [title], which wraps rather
 * than clips, and the lock. Back and the system back both return one page.
 */
@Composable
fun ClearAirPageTopBar(title: String, backLabel: String?, onBack: () -> Unit, onLock: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = TOP_BAR_HEIGHT).padding(horizontal = TOP_BAR_SIDE, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClearAirIconButton(glyph = ClearAirGlyph.BACK, label = backLabel?.let { "Back to $it" } ?: "Back", onClick = onBack)
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(horizontal = TOP_BAR_TITLE_GAP),
            style = topBarTitle,
            color = palette.ink,
            textAlign = TextAlign.Start,
        )
        ClearAirIconButton(glyph = ClearAirGlyph.LOCK, label = "Lock", onClick = onLock)
    }
}

/** A 48 dp icon button in ink: the touch target of a top bar's icon, labelled for TalkBack. */
@Composable
fun ClearAirIconButton(glyph: ClearAirGlyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Box(
        modifier = modifier.size(ClearAirSpacing.touchTarget).pressable(onClick = onClick, label = label),
        contentAlignment = Alignment.Center,
    ) {
        ClearAirIcon(glyph = glyph, color = palette.ink, size = 22.dp)
    }
}

/** A press target with a button role and a spoken [label], which scales as a Clear Air press does. */
private fun Modifier.pressable(onClick: () -> Unit, label: String): Modifier =
    semantics {
        role = Role.Button
        contentDescription = label
    }.lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
