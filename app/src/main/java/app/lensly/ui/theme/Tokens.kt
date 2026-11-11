package app.lensly.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale in dp. Use these for padding and gaps instead of raw literals. */
object LenslySpacing {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 48.dp
}

/** Corner radii in dp. For a fully round end, use [LenslyPillShape] instead of a fixed radius. */
object LenslyRadius {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
}

/** Fully round ends for pills, chips and buttons at any height. */
val LenslyPillShape = RoundedCornerShape(percent = 50)

/** Material shape scale, so Material components use the same corner radii as Lensly's own panels. */
val LenslyShapes = Shapes(
    extraSmall = RoundedCornerShape(LenslyRadius.xs),
    small = RoundedCornerShape(LenslyRadius.sm),
    medium = RoundedCornerShape(LenslyRadius.md),
    large = RoundedCornerShape(LenslyRadius.lg),
    extraLarge = RoundedCornerShape(LenslyRadius.xl),
)
