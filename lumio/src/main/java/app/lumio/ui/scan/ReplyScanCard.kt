package app.lumio.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.ui.camera.FrameSource
import app.lensly.ui.camera.openAppSettings
import app.lensly.ui.qr.QrScanner
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.rememberLumioHaptics

/** How far a reply has been read: [received] of its [expected] parts. */
data class ReplyReading(val received: Int, val expected: Int)

/**
 * The camera card of the reply scan, as the Send mock draws it: a dark card with the live camera, four paper corner brackets, the words that say how many
 * parts are read, and a bar with a segment per part that fills amber, with a tick, as each one is read. While the camera is refused, the Camera Denied note takes the
 * card's place. [frameSource] is for tests, which pass their own frames.
 */
@Composable
fun ReplyScanCard(
    reading: ReplyReading?,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
    frameSource: FrameSource? = null,
) {
    val palette = LocalClearAirPalette.current
    val camera = rememberCameraAccess(frameSource)
    val context = LocalContext.current
    val haptics = rememberLumioHaptics()
    LaunchedEffect(reading?.received) {
        if (reading != null) haptics.tick()
    }
    val frame = modifier.fillMaxWidth().height(CARD_HEIGHT)
    if (camera.refused && !camera.granted) {
        CameraDeniedNote(onOpenSettings = { context.openAppSettings() }, modifier = frame)
        return
    }
    Box(
        modifier = frame
            .clip(RoundedCornerShape(CARD_CORNER))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(CARD_CENTRE, CARD_EDGE),
                        center = Offset(size.width * 0.5f, size.height * 0.4f),
                        radius = size.width * 0.9f,
                    ),
                )
            },
    ) {
        if (camera.granted) QrScanner(onText = onText, modifier = Modifier.fillMaxSize(), frameSource = frameSource, viewfinder = false)
        Box(modifier = Modifier.fillMaxSize().drawBehind { drawBrackets(palette.paper.copy(alpha = BRACKET_ALPHA)) })
        reading?.let {
            Text(
                text = "Reading Lensly's reply · ${it.received} of ${it.expected}",
                style = ClearAirType.time,
                color = palette.paper.copy(alpha = CAPTION_ALPHA),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 30.dp),
            )
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                repeat(it.expected) { part ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (part < it.received) palette.spark else palette.paper.copy(alpha = TRACK_ALPHA)),
                    )
                }
            }
        }
    }
}

/** The four corner brackets: each arm is 34 dp long and 3 dp thick, with a 12 dp outer corner, inset 62 dp at the sides, 46 dp above and 62 dp below. */
private fun DrawScope.drawBrackets(color: Color) {
    val stroke = BRACKET_WIDTH.toPx()
    val half = stroke / 2f
    val arm = BRACKET_ARM.toPx() - half
    val radius = BRACKET_RADIUS.toPx() - half
    val left = BRACKET_SIDE.toPx() + half
    val right = size.width - BRACKET_SIDE.toPx() - half
    val top = BRACKET_TOP.toPx() + half
    val bottom = size.height - BRACKET_BOTTOM.toPx() - half
    val path = Path().apply {
        moveTo(left, top + arm)
        lineTo(left, top + radius)
        arcTo(Rect(left, top, left + 2 * radius, top + 2 * radius), 180f, 90f, false)
        lineTo(left + arm, top)
        moveTo(right - arm, top)
        lineTo(right - radius, top)
        arcTo(Rect(right - 2 * radius, top, right, top + 2 * radius), 270f, 90f, false)
        lineTo(right, top + arm)
        moveTo(right, bottom - arm)
        lineTo(right, bottom - radius)
        arcTo(Rect(right - 2 * radius, bottom - 2 * radius, right, bottom), 0f, 90f, false)
        lineTo(right - arm, bottom)
        moveTo(left + arm, bottom)
        lineTo(left + radius, bottom)
        arcTo(Rect(left, bottom - 2 * radius, left + 2 * radius, bottom), 90f, 90f, false)
        lineTo(left, bottom - arm)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Butt))
}

private val CARD_HEIGHT = 316.dp
private val CARD_CORNER = 26.dp
private val CARD_CENTRE = Color(0xFF2A2630)
private val CARD_EDGE = Color(0xFF0B0A0D)
private val BRACKET_ARM = 34.dp
private val BRACKET_WIDTH = 3.dp
private val BRACKET_RADIUS = 12.dp
private val BRACKET_SIDE = 62.dp
private val BRACKET_TOP = 46.dp
private val BRACKET_BOTTOM = 62.dp
private const val BRACKET_ALPHA = 0.9f
private const val CAPTION_ALPHA = 0.85f
private const val TRACK_ALPHA = 0.22f
