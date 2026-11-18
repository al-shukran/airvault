package app.lensly.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The Material shape scale of the Lumen theme, section 1.3 of the Phase 2.8 design: 8, 12, 16, 24 and 32 dp. The islands are large and the
 * sheets' top corners extra large. The fully round ends are [LenslyPillShape]. Components read these through `MaterialTheme.shapes`.
 */
val LumenShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
