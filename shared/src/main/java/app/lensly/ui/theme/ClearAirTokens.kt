package app.lensly.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Clear Air spacing: a 4 dp base, a 20 dp page gutter on every screen, and touch targets of at least 48 dp. */
object ClearAirSpacing {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val gutter: Dp = 20.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 48.dp

    /** The smallest square a control may be touched in, including an icon. */
    val touchTarget: Dp = 48.dp
}

/** The one primary action of a screen is a capsule, and nothing else is. */
val ClearAirCapsule = RoundedCornerShape(percent = 50)

/**
 * The Clear Air shape scale: 6 dp for tags, 12 for the wallet emblem tile, 16 for fields and notes, 24 for QR cards and the camera, and 28 for
 * sheet tops.
 */
val ClearAirShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
