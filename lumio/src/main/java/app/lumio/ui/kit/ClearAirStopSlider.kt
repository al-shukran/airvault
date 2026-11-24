package app.lumio.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import kotlin.math.roundToInt

/**
 * A discrete slider with one stop per entry of [labels], such as Slow, Normal and Fast. The track has a tick at each stop and the spark thumb sits
 * on [selected]. A tap or a drag moves the thumb to the nearest stop, and [onSelect] gets its index. The labels sit under the track, and the
 * selected one is in full ink, so the choice reads in words as well as position.
 */
@Composable
fun ClearAirStopSlider(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    val last = (labels.size - 1).coerceAtLeast(1)
    var width by remember { mutableFloatStateOf(0f) }
    fun stopAt(x: Float): Int = if (width <= 0f) selected else ((x / width).coerceIn(0f, 1f) * last).roundToInt()
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ClearAirSpacing.touchTarget)
                .semantics {
                    // TalkBack adjusts the slider one stop at a time, and reads the stop's name rather than a number.
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        selected.toFloat(),
                        0f..last.toFloat(),
                        (labels.size - 2).coerceAtLeast(0),
                    )
                    stateDescription = labels.getOrElse(selected) { "" }
                    setProgress { target ->
                        onSelect(target.roundToInt().coerceIn(0, last))
                        true
                    }
                }
                .onSizeChanged { width = it.width.toFloat() }
                .pointerInput(labels.size) { detectTapGestures { onSelect(stopAt(it.x)) } }
                .pointerInput(labels.size) {
                    detectHorizontalDragGestures(
                        onDragStart = { onSelect(stopAt(it.x)) },
                        onHorizontalDrag = { change, _ -> onSelect(stopAt(change.position.x)) },
                    )
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.Center)
                    .background(palette.hairline),
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val y = size.height / 2
                labels.indices.forEach { index ->
                    drawCircle(
                        color = if (index <= selected) palette.ink else palette.ink2,
                        radius = 3.dp.toPx(),
                        center = Offset(size.width * index / last, y),
                    )
                }
                val thumb = Offset(size.width * selected / last, y)
                drawCircle(color = palette.spark, radius = 11.dp.toPx(), center = thumb)
                drawCircle(color = palette.ink, radius = 11.dp.toPx(), center = thumb, style = Stroke(width = 1.dp.toPx()))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEachIndexed { index, label ->
                Text(
                    text = label,
                    style = ClearAirType.label,
                    color = if (index == selected) palette.ink else palette.ink2,
                )
            }
        }
    }
}
