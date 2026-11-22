package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** Where a [ClearAirTimelineStep] stands: done, the one happening now, or still to come. */
enum class ClearAirStepState { Done, Now, Todo }

/** One step of a [ClearAirTimeline]: its [title], a [detail] under it, and its [state]. */
class ClearAirTimelineStep(val title: String, val detail: String, val state: ClearAirStepState)

/**
 * The steps of a transfer, top to bottom: a vault-blue check for a step done, a spark dot for the step now, and a hairline ring for the steps
 * still to come. Each step says its state in its mark and its words, so the marks alone never carry it.
 */
@Composable
fun ClearAirTimeline(steps: List<ClearAirTimelineStep>, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Column(modifier = modifier.fillMaxWidth()) {
        steps.forEach { step ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = ClearAirSpacing.xs),
                verticalAlignment = Alignment.Top,
            ) {
                val mark = Modifier.size(22.dp).clip(CircleShape)
                Box(
                    modifier = when (step.state) {
                        ClearAirStepState.Done -> mark.background(palette.vaultBlue)
                        ClearAirStepState.Now -> mark.background(palette.spark)
                        ClearAirStepState.Todo -> mark.border(1.dp, palette.hairline, CircleShape)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (step.state == ClearAirStepState.Done) {
                        Text(text = "✓", style = ClearAirType.label, color = palette.background)
                    }
                }
                Spacer(Modifier.width(ClearAirSpacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = step.title,
                        style = ClearAirType.rowTitle,
                        color = if (step.state == ClearAirStepState.Todo) palette.ink2 else palette.ink,
                    )
                    Text(text = step.detail, style = ClearAirType.body, color = palette.ink2)
                }
            }
        }
    }
}
