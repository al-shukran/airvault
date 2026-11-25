package app.lumio.ui.kit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.LocalClearAirPalette
import app.lensly.ui.theme.LocalReducedMotion

/** The spark's square, in dp, before it is turned into a diamond. */
private val SPARK_SIZE = 14.dp

/** The spark's turn: a square at 45 degrees is the diamond of the brand. */
private const val SPARK_TURN = 45f

/** The spark breathes between these opacities while a refresh runs, the only loop in a list. */
private const val BREATH_LOW = 0.5f

/**
 * A pull to refresh for a list of balances. The spark draws in under the top bar as the owner pulls, and gives a select haptic at the threshold. While a
 * pulled refresh runs it breathes, and under reduced motion it holds still. A refresh the owner did not pull draws no spark over the list: its
 * freshness line says "Checking…" instead.
 */
@Composable
fun ClearAirRefreshBox(
    refreshByPull: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val haptics = rememberLumioHaptics()
    val pastThreshold by remember { derivedStateOf { state.distanceFraction >= 1f } }
    LaunchedEffect(pastThreshold) {
        if (pastThreshold) haptics.select()
    }
    PullToRefreshBox(
        isRefreshing = refreshByPull,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        contentAlignment = Alignment.TopCenter,
        indicator = {
            val pulled = if (refreshByPull) 1f else state.distanceFraction.coerceIn(0f, 1f)
            ClearAirSpark(
                progress = pulled,
                breathing = refreshByPull,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        alpha = pulled
                        translationY = pulled * PullToRefreshDefaults.IndicatorMaxDistance.toPx() - SPARK_SIZE.toPx()
                    },
            )
        },
        content = content,
    )
}

/**
 * The spark: a diamond in the palette's spark colour, [size] across before it turns, that grows with [progress]. While [breathing] its opacity
 * eases between two values, and under reduced motion it holds still.
 */
@Composable
internal fun ClearAirSpark(progress: Float, breathing: Boolean, modifier: Modifier = Modifier, size: Dp = SPARK_SIZE) {
    val spark = LocalClearAirPalette.current.spark
    val breath = breathOpacity(breathing && !LocalReducedMotion.current)
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                rotationZ = SPARK_TURN
                scaleX = progress
                scaleY = progress
                alpha = breath
            }
            .background(spark),
    )
}

/** The opacity of a breathing spark, or full when [breathing] is false. */
@Composable
private fun breathOpacity(breathing: Boolean): Float {
    if (!breathing) return 1f
    val transition = rememberInfiniteTransition(label = "clear air spark")
    val opacity by transition.animateFloat(
        initialValue = BREATH_LOW,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(ClearAirMotion.BREATH_MS, easing = ClearAirMotion.breathEasing), RepeatMode.Reverse),
        label = "clear air spark breath",
    )
    return opacity
}
