package app.lensly.ui.home

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceLow
import app.lensly.ui.wallet.WALLET_GUTTER

private val HERO_HEIGHT = 232.dp
private const val BLADES = 6
private const val BLADE_SWEEP = 46f
private const val ONE_TURN_MS = 8_000

/**
 * The full-width artwork at the top of Home: an aperture of six blades that turns once every eight seconds, over a soft radial
 * glow in the accent colour. The wordmark and one status line sit on it. The drawing is a Canvas, so it needs no image. When
 * the system turns animations off, the aperture is drawn still.
 */
@Composable
internal fun HomeHero(
    status: String,
    onSettings: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val motion = remember(context) { animatorScale(context) }
    val rotation = if (motion == 0f) 0f else turningAperture()
    val haptics = rememberKeyHaptics()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HERO_HEIGHT)
            .clip(RoundedCornerShape(bottomStart = LenslyRadius.xl, bottomEnd = LenslyRadius.xl))
            .background(LenslySurfaceLow),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(LenslySurfaceLow, LenslyBlack)))
            val centre = Offset(size.width * 0.76f, size.height * 0.44f)
            val radius = size.minDimension * 0.30f
            val glow = radius * 2.6f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        LenslyAccent.copy(alpha = 0.40f),
                        LenslyAccent.copy(alpha = 0.10f),
                        Color.Transparent,
                    ),
                    center = centre,
                    radius = glow,
                ),
                radius = glow,
                center = centre,
            )
            drawCircle(
                color = LenslyOnSurface.copy(alpha = 0.30f),
                radius = radius,
                center = centre,
                style = Stroke(width = 1.dp.toPx()),
            )
            rotate(rotation, pivot = centre) {
                repeat(BLADES) { index ->
                    drawArc(
                        color = LenslyOnSurface.copy(alpha = 0.22f),
                        startAngle = index * (360f / BLADES),
                        sweepAngle = BLADE_SWEEP,
                        useCenter = false,
                        topLeft = Offset(centre.x - radius, centre.y - radius),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            drawCircle(color = LenslyBlack, radius = radius * 0.44f, center = centre)
            drawCircle(
                color = LenslyAccent.copy(alpha = 0.90f),
                radius = radius * 0.10f,
                center = centre,
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = LenslySpacing.xs, end = LenslySpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onSettings, modifier = Modifier.tapHaptic(haptics)) {
                Icon(imageVector = Icons.Filled.Settings, contentDescription = "Settings", tint = LenslyOnSurface)
            }
            IconButton(onClick = onLock, modifier = Modifier.tapHaptic(haptics)) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Lock the vault",
                    tint = LenslyOnSurface,
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = WALLET_GUTTER, vertical = LenslySpacing.lg),
            verticalArrangement = Arrangement.spacedBy(LenslySpacing.xxs),
        ) {
            Text(
                text = "Lensly",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineLarge,
                color = LenslyOnSurface,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The aperture's angle, one turn every eight seconds. */
@Composable
private fun turningAperture(): Float {
    val transition = rememberInfiniteTransition(label = "aperture")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ONE_TURN_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "blades",
    )
    return angle
}

/** The system's animator duration scale: 0 when the owner has turned animations off. */
private fun animatorScale(context: Context): Float = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}.getOrDefault(1f)
