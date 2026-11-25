package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The corner radius of the segmented control's track, the proposal's 14 dp. */
private val TRACK_CORNER = 14.dp

/** The corner radius of a segment inside the track. */
private val SEGMENT_CORNER = 11.dp

/** The height of a segment: the mock's 8 dp of padding around a 20 dp line, so the track is 42 dp tall. Compose widens a press to 48 dp around it, so the touch target keeps its size. */
private val SEGMENT_HEIGHT = 36.dp

/**
 * Two or more [options] as one track: the [selected] option is filled with ink and its label is paper, the others sit on the sunk track. Choosing one calls [onSelect] at
 * once. Each option is a radio button in TalkBack.
 */
@Composable
fun <T> ClearAirSegmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TRACK_CORNER))
            .background(palette.raised)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(SEGMENT_HEIGHT)
                    .clip(RoundedCornerShape(SEGMENT_CORNER))
                    .background(if (active) palette.primary else Color.Transparent)
                    .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(option) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = ClearAirType.rowTitle.copy(fontSize = 14.sp),
                    color = if (active) palette.onPrimary else palette.ink2,
                    maxLines = 1,
                )
            }
        }
    }
}
