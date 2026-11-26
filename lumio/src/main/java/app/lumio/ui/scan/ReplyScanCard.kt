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
            .drawBehind { drawCameraBackdrop() },
    ) {
        if (camera.granted) QrScanner(onText = onText, modifier = Modifier.fillMaxSize(), frameSource = frameSource, viewfinder = false)
        Box(modifier = Modifier.fillMaxSize().drawBehind { drawCameraBrackets(palette.paper) })
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

private val CARD_HEIGHT = 316.dp
private val CARD_CORNER = 26.dp
private const val CAPTION_ALPHA = 0.85f
private const val TRACK_ALPHA = 0.22f
