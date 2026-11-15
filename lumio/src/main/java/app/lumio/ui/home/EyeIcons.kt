package app.lumio.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The eye that shows a hidden total, and the eye with a slash that hides a shown one. The core icon set has neither, and the project ships no extended
 * set, so they are outlines at 24 dp in the same style as the Lensly glyphs. They are white, and the call site tints them.
 */
internal object EyeIcons {
    /** An open eye: the total is hidden, and a tap shows it. */
    val Show: ImageVector by lazy { eye(name = "EyeShow", slashed = false) }

    /** An eye with a slash: the total is shown, and a tap hides it. */
    val Hide: ImageVector by lazy { eye(name = "EyeHide", slashed = true) }

    private fun eye(name: String, slashed: Boolean): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
            .addPath(
                pathData = addPathNodes("M2 12C4.4 7.6 7.9 5.5 12 5.5s7.6 2.1 10 6.5c-2.4 4.4-5.9 6.5-10 6.5S4.4 16.4 2 12z"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .addPath(
                pathData = addPathNodes("M12 9.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        if (slashed) {
            builder.addPath(
                pathData = addPathNodes("M4 4L20 20"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            )
        }
        return builder.build()
    }
}
