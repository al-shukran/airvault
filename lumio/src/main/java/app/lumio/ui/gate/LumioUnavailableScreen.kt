package app.lumio.ui.gate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIconButton
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.theme.LumioGutter

/**
 * The page that shows when Lumio cannot open its vault: "Not Available", one sentence, and the build's reason when [detail] is set. It holds no
 * secret and names no factor. Close leaves the gate, as the top bar's close does.
 */
@Composable
fun LumioUnavailableScreen(detail: String?, onClose: () -> Unit) {
    val palette = LocalClearAirPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = ClearAirSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClearAirIconButton(glyph = ClearAirGlyph.CLOSE, label = "Close", onClick = onClose)
        }
        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = LumioGutter)) {
            Text(text = "Not Available", style = ClearAirType.titleXL, color = palette.ink)
            Spacer(Modifier.height(ClearAirSpacing.sm))
            Text(text = "Lumio can’t open on this device.", style = ClearAirType.body, color = palette.ink)
            if (detail != null) {
                Spacer(Modifier.height(ClearAirSpacing.sm))
                Text(text = detail, style = ClearAirType.label, color = palette.ink2)
            }
        }
        ClearAirPrimaryButton(
            text = "Close",
            onClick = onClose,
            modifier = Modifier.padding(horizontal = ClearAirSpacing.md, vertical = FOOTER_PADDING),
        )
    }
}

private val BAR_HEIGHT = 52.dp
private val FOOTER_PADDING = 20.dp
