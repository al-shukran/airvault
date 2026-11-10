package app.lensly.ui.camera

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Flash glyphs. The core icon set has none, and the extended set is not shipped. */
internal object CameraIcons {
    val FlashOn: ImageVector by lazy { flashIcon(name = "FlashOn", slashed = false) }
    val FlashOff: ImageVector by lazy { flashIcon(name = "FlashOff", slashed = true) }

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
}
