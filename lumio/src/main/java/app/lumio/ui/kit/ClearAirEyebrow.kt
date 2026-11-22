package app.lumio.ui.kit

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.em
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** An eyebrow: the small capitals that name a group of rows. Eyebrows are all caps by design, so [text] is set in capitals here. */
@Composable
fun ClearAirEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(top = ClearAirSpacing.sm, bottom = ClearAirSpacing.xs),
        style = ClearAirType.eyebrow.copy(letterSpacing = 0.09.em),
        color = LocalClearAirPalette.current.ink2,
    )
}
