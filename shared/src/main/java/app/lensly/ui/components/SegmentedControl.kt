package app.lensly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslySpacing

/** The height of a segmented control's track. */
private val TRACK_HEIGHT = 48.dp

/**
 * A segmented control: one choice of [options], shown as a row of segments in one pill. The chosen segment is filled and reads as
 * selected to a screen reader. A tap on another segment calls [onSelect] with it. Use it for a choice between two or three
 * settings that the reader sees side by side, such as a network side. It is not a button: a tap only chooses.
 */
@Composable
fun <T> LenslySegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
            .clip(LenslyPillShape)
            .background(colors.surfaceContainer)
            .border(1.dp, colors.outline, LenslyPillShape)
            .padding(LenslySpacing.xxs)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xxs),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(LenslyPillShape)
                    .background(if (isSelected) colors.surfaceVariant else colors.surfaceContainer)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) colors.onSurface else colors.onSurfaceVariant,
                )
            }
        }
    }
}
