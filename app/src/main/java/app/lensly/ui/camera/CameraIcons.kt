package app.lensly.ui.camera

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Camera glyphs. The core icon set has none of these, and the extended set is not shipped. Every glyph is white and is
 * tinted at the call site.
 */
internal object CameraIcons {
    val FlashOn: ImageVector by lazy { flashIcon(name = "FlashOn", slashed = false) }
    val FlashOff: ImageVector by lazy { flashIcon(name = "FlashOff", slashed = true) }

    /** A camera body with a lens, for the permission and unavailable states. */
    val Camera: ImageVector by lazy {
        strokeIcon(
            name = "Camera",
            "M9 5L7.5 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3.5L15 5z",
            "M12 10a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z",
        )
    }

    /** A picture frame with a hill and a sun, for the empty gallery. */
    val Photos: ImageVector by lazy {
        strokeIcon(
            name = "Photos",
            "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
            "M4 17l4.5-5 3.5 3.8 2.5-2.8L20 18",
            "M16.5 8a1 1 0 1 0 0 2a1 1 0 1 0 0-2z",
        )
    }

    private fun flashIcon(name: String, slashed: Boolean): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes("M7 2v11h3v9l7-12h-4l4-8z"),
            fill = SolidColor(Color.White),
        )
        if (slashed) {
            builder.addPath(
                pathData = addPathNodes("M3 3L21 21"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
            )
        }
        return builder.build()
    }

    /** An outline glyph: each path is stroked with rounded ends and joins at a 24 dp viewport. */
    private fun strokeIcon(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        paths.forEach { data ->
            builder.addPath(
                pathData = addPathNodes(data),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
