package app.lumio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Lumen's corner radii, section 1.3. The islands are [lg], the sheets' top corners are [xl]. */
object LumioRadius {
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
}

/** Fully round ends, for the primary button (56 dp tall) and the tags. */
val LumioFullShape = RoundedCornerShape(percent = 50)

/** The Material shape scale in Lumen's radii, so Material components match the islands and the sheets. */
val LumioShapes = Shapes(
    extraSmall = RoundedCornerShape(LumioRadius.xs),
    small = RoundedCornerShape(LumioRadius.sm),
    medium = RoundedCornerShape(LumioRadius.md),
    large = RoundedCornerShape(LumioRadius.lg),
    extraLarge = RoundedCornerShape(LumioRadius.xl),
)

/** The page gutter of Lumio's screens. Spacing otherwise follows the Lensly scale, `LenslySpacing`. */
val LumioGutter: Dp = 20.dp
