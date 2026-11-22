package app.lumio.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirIconButton

/** The height of the search field: a capsule-like field, 48 dp tall. */
private val FIELD_HEIGHT = 48.dp

/** The corner radius of the search field, the proposal's 24 dp. */
private val FIELD_CORNER = 24.dp

/**
 * The search sheet's content: the search field and the close action. Search results are not built yet, so the field shows what it searches and
 * says how to search, and it takes no input. The sheet opens over the page it was called from and closes with its own Close action.
 */
@Composable
fun SearchSheet(onClose: () -> Unit) {
    val palette = LocalClearAirPalette.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                Text(text = "Search anything across your wallet", style = ClearAirType.body, color = palette.ink2, maxLines = 1)
            }
            ClearAirIconButton(glyph = ClearAirGlyph.CLOSE, label = "Close", onClick = onClose)
        }
        Spacer(Modifier.height(ClearAirSpacing.sm))
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.xxs)) {
            Text(text = "Try an address, a ticker or a chain.", style = ClearAirType.body, color = palette.ink2)
        }
    }
}
