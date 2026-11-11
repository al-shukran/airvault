package app.lensly.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Glyphs the core icon set lacks: the QR marks for pairing and scanning, and a wallet. The project ships no extended icon set,
 * so these are outlines at 24 dp, drawn the way the camera glyphs are. They are white, and the call site tints them.
 */
internal object LenslyIcons {
    /** Four QR squares, for pairing. */
    val QrCode: ImageVector by lazy {
        outline(
            "QrCode",
            "M4 4h6v6H4z",
            "M14 4h6v6h-6z",
            "M4 14h6v6H4z",
            "M14 14h2v2h-2z",
            "M18 14h2v2h-2z",
            "M14 18h2v2h-2z",
            "M18 18h2v2h-2z",
        )
    }

    /** A viewfinder with a scan line, for the scan button. */
    val QrScan: ImageVector by lazy {
        outline(
            "QrScan",
            "M4 8V6a2 2 0 0 1 2-2h2",
            "M16 4h2a2 2 0 0 1 2 2v2",
            "M20 16v2a2 2 0 0 1-2 2h-2",
            "M8 20H6a2 2 0 0 1-2-2v-2",
            "M7 12h10",
            strokeWidth = 2f,
        )
    }

    /** A wallet with its clasp, for the list of wallets. */
    val Wallets: ImageVector by lazy {
        outline(
            "Wallets",
            "M4 8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z",
            "M16 12.5h2",
        )
    }

    /** An outline glyph: each path is stroked with rounded ends and joins at a 24 dp viewport. */
    private fun outline(name: String, vararg paths: String, strokeWidth: Float = 1.5f): ImageVector {
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
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
