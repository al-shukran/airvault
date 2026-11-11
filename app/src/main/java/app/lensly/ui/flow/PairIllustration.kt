package app.lensly.ui.flow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyMuted
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyOutlineInteractive
import app.lensly.ui.theme.LenslySurfaceContainer

/** The pairing code as a grid of modules, one string per row. A "1" is a dark module. */
private val CODE_GRID = listOf(
    "1110101",
    "1000101",
    "1011101",
    "0001000",
    "1011011",
    "1000101",
    "1110101",
)

private val ILLUSTRATION_HEIGHT = 156.dp

/**
 * Phone scans phone: Lensly's phone on the left shows the pairing code, and the online wallet's phone on the right reads it with
 * its camera. A dashed beam runs from the code to the camera. It is drawn with Canvas, so it needs no image.
 */
@Composable
internal fun PairIllustration(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .height(ILLUSTRATION_HEIGHT)
            .semantics { contentDescription = "Your phone shows a code, and the online wallet's camera scans it." },
    ) {
        val centreY = size.height / 2f
        val phoneHeight = size.height * 0.9f
        val phoneWidth = phoneHeight * 0.5f
        val top = centreY - phoneHeight / 2f
        val leftX = size.width * 0.22f - phoneWidth / 2f
        val rightX = size.width * 0.78f - phoneWidth / 2f
        val corner = CornerRadius(18.dp.toPx())
        val outline = Stroke(width = 1.5.dp.toPx())
        val phoneSize = Size(phoneWidth, phoneHeight)

        // Lensly's phone, with the code on its screen.
        drawRoundRect(LenslySurfaceContainer, Offset(leftX, top), phoneSize, corner)
        drawRoundRect(LenslyOutlineInteractive, Offset(leftX, top), phoneSize, corner, style = outline)
        val module = phoneWidth * 0.08f
        val codeLeft = leftX + (phoneWidth - module * CODE_GRID.first().length) / 2f
        val codeTop = centreY - module * CODE_GRID.size / 2f
        CODE_GRID.forEachIndexed { row, line ->
            line.forEachIndexed { column, cell ->
                if (cell == '1') {
                    drawRect(
                        color = LenslyOnSurface,
                        topLeft = Offset(codeLeft + column * module, codeTop + row * module),
                        size = Size(module, module),
                    )
                }
            }
        }

        // The online wallet's phone, with a viewfinder and a scan line across it.
        drawRoundRect(LenslySurfaceContainer, Offset(rightX, top), phoneSize, corner)
        drawRoundRect(LenslyOutlineInteractive, Offset(rightX, top), phoneSize, corner, style = outline)
        val frame = phoneWidth * 0.62f
        val frameLeft = rightX + (phoneWidth - frame) / 2f
        val frameTop = centreY - frame / 2f
        viewfinderCorners(frameLeft, frameTop, frame)
        drawLine(
            color = LenslyAccent.copy(alpha = 0.8f),
            start = Offset(frameLeft, centreY),
            end = Offset(frameLeft + frame, centreY),
            strokeWidth = 1.dp.toPx(),
        )

        // The beam from the code to the camera.
        val gap = 10.dp.toPx()
        drawLine(
            color = LenslyMuted,
            start = Offset(leftX + phoneWidth + gap, centreY),
            end = Offset(rightX - gap, centreY),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
        )
    }
}

/**
 * The four corners of a square viewfinder at ([left], [top]) with side [side]. Each corner is two short arms in the accent colour,
 * one along each edge it sits on.
 */
private fun DrawScope.viewfinderCorners(left: Float, top: Float, side: Float) {
    val arm = side * 0.28f
    val stroke = 2.dp.toPx()
    val right = left + side
    val bottom = top + side
    // Each entry is a corner, the end of its arm along the top or bottom edge, and the end of its arm along the side.
    val corners = listOf(
        Triple(Offset(left, top), Offset(left + arm, top), Offset(left, top + arm)),
        Triple(Offset(right, top), Offset(right - arm, top), Offset(right, top + arm)),
        Triple(Offset(left, bottom), Offset(left + arm, bottom), Offset(left, bottom - arm)),
        Triple(Offset(right, bottom), Offset(right - arm, bottom), Offset(right, bottom - arm)),
    )
    corners.forEach { (corner, along, down) ->
        drawLine(LenslyAccent, corner, along, strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(LenslyAccent, corner, down, strokeWidth = stroke, cap = StrokeCap.Round)
    }
}
