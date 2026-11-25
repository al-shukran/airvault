package app.lumio.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.LocalClearAirPalette
import app.lensly.ui.theme.LocalReducedMotion

/** The gap above a sheet, so the page above it still shows: the proposal's sheet from the top 44 dp. */
private val SHEET_TOP = 44.dp

/** The corner radius of a sheet's top, the proposal's 28 dp. */
private val SHEET_CORNER = 28.dp

/** The light theme lifts a sheet with a faint ink shadow, and the dark theme has none. */
private val SHEET_SHADOW = 10.dp
private val SHADOW_INK = Color(0xFF2B2530).copy(alpha = 0.10f)
private val sheetShape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER)

/**
 * The one modal sheet of Lumio. It rises from the bottom on the sheet spring over a scrim that fades in, and the page beneath stays still. It has
 * no place of its own: a short task opens it over a page, and [onClose] closes it. Tapping the scrim closes it too. A caller holds at most one
 * sheet, so a sheet never opens over another one. Reduced motion cuts the rise to a cross-fade.
 */
@Composable
fun ClearAirSheet(visible: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier, top: Dp = SHEET_TOP, content: @Composable () -> Unit) {
    val palette = LocalClearAirPalette.current
    val reduced = LocalReducedMotion.current
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(ClearAirMotion.SCRIM_MS)),
            exit = fadeOut(tween(ClearAirMotion.SCRIM_MS)),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(palette.scrim).clickable(onClick = onClose))
        }
        AnimatedVisibility(
            visible = visible,
            enter = if (reduced) fadeIn(tween(ClearAirMotion.CROSS_FADE_MS)) else slideInVertically(sheetOffset) { it },
            exit = if (reduced) fadeOut(tween(ClearAirMotion.CROSS_FADE_MS)) else slideOutVertically(sheetOffset) { it },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = top)
                    .then(if (isSystemInDarkTheme()) Modifier else Modifier.shadow(SHEET_SHADOW, sheetShape, ambientColor = SHADOW_INK, spotColor = SHADOW_INK))
                    .clip(sheetShape)
                    .background(palette.sheet),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp, bottom = 2.dp)
                            .align(Alignment.CenterHorizontally)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.ink3.copy(alpha = 0.55f)),
                    )
                    // No weight: a sheet that a scrolling page hosts has no height to share out, and a weighted child would get none.
                    content()
                }
            }
        }
    }
}

/** The sheet's rise: spring ζ0.9, k520, the values of [ClearAirMotion.sheet], for the offset the slide animates. */
private val sheetOffset = spring<IntOffset>(dampingRatio = 0.9f, stiffness = 520f)
