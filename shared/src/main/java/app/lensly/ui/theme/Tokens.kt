package app.lensly.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale in dp. Use these for padding and gaps instead of raw literals. Both themes share it. */
object LenslySpacing {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 48.dp
}

/** Fully round ends for pills, chips and buttons at any height. Both themes share it. */
val LenslyPillShape = RoundedCornerShape(percent = 50)
