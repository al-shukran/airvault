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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** Where a [ClearAirTimelineStep] stands: done, the one happening now, or still to come. */
enum class ClearAirStepState { Done, Now, Todo }

/** One step of a [ClearAirTimeline]: its [title], a [detail] under it, its [state], and the clock [time] at its right, when it has one. */
class ClearAirTimelineStep(val title: String, val detail: String, val state: ClearAirStepState, val time: String? = null)

private val MARK = 22.dp
private val LINE_WIDTH = 1.5.dp

/**
 * The steps of a transfer on a card, top to bottom, as the mock draws them: an ink disc with a check for a step done, an amber diamond with a soft halo
 * for the step now, and an ink3 ring for the steps still to come, joined by a line that is ink below a step done. Each step says its state in its mark
 * and its words, so the marks alone never carry it. A step's [ClearAirTimelineStep.time] sits at its right, in the amber text colour for the step now.
 */
@Composable
fun ClearAirTimeline(steps: List<ClearAirTimelineStep>, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(palette.sheet)
            .border(1.dp, palette.hairline, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        steps.forEachIndexed { index, step ->
            val last = index == steps.lastIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        if (!last) {
                            val x = 10.5.dp.toPx()
                            drawLine(
                                color = if (step.state == ClearAirStepState.Done) palette.ink else palette.hairline,
                                start = Offset(x + LINE_WIDTH.toPx() / 2, 38.dp.toPx()),
                                end = Offset(x + LINE_WIDTH.toPx() / 2, size.height + 10.dp.toPx()),
                                strokeWidth = LINE_WIDTH.toPx(),
                            )
                        }
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                val mark = Modifier.padding(top = 1.dp).size(MARK)
                Box(
                    modifier = when (step.state) {
                        ClearAirStepState.Done -> mark.clip(CircleShape).background(palette.ink)
                        ClearAirStepState.Now -> mark
                        ClearAirStepState.Todo -> mark.border(1.5.dp, palette.ink3, CircleShape)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (step.state == ClearAirStepState.Done) {
                        ClearAirIcon(glyph = ClearAirGlyph.CHECK, color = palette.background, size = 13.dp, stroke = 2.6f)
                    }
                    if (step.state == ClearAirStepState.Now) {
                        Box(modifier = Modifier.size(20.dp).rotate(45f).background(palette.spark.copy(alpha = 0.22f)))
                        Box(modifier = Modifier.size(10.dp).rotate(45f).background(palette.spark))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = step.title,
                        style = ClearAirType.rowTitle,
                        color = if (step.state == ClearAirStepState.Todo) palette.ink2 else palette.ink,
                    )
                    Text(text = step.detail, style = ClearAirType.caption, color = palette.ink2)
                }
                step.time?.let { time ->
                    Text(
                        text = time,
                        style = ClearAirType.time,
                        color = if (step.state == ClearAirStepState.Now) palette.sparkText else palette.ink2,
                        modifier = Modifier.padding(start = 14.dp, top = 2.dp),
                    )
                }
            }
        }
    }
}
