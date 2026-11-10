package app.lensly.ui.gate

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.theme.LenslyOnSurface

/**
 * Unlock screen. It shows no error text, ever: a wrong password just clears the field, so the screen
 * never says which factor failed or how many tries are left.
 *
 * While [busy] (key derivation takes about a second) a dot-matrix progress row replaces the empty
 * space under the field, and the keyboard ignores input.
 */
@Composable
fun UnlockScreen(
    length: Int,
    busy: Boolean,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        DotHeadline(text = "UNLOCK")

        Spacer(Modifier.weight(1f))

        PasswordField(length = length)
        Spacer(Modifier.height(12.dp))
        // Reserved height, so the keyboard does not move while the progress row comes and goes.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PROGRESS_SLOT_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) DotProgress()
        }

        Spacer(Modifier.weight(1f))

        SecureKeyboard(
            onKey = onKey,
            onBackspace = onBackspace,
            onDone = onSubmit,
            enabled = !busy,
        )
    }
}

private const val PROGRESS_DOTS = 7
private val PROGRESS_DOT_DIAMETER = 6.dp
private val PROGRESS_PITCH = 12.dp
private val PROGRESS_SLOT_HEIGHT = 12.dp

/**
 * Subtle dot-matrix progress: a row of dots with a soft highlight that moves along it. The highlight
 * wraps around, so it never stalls.
 */
@Composable
private fun DotProgress(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "unlock-progress")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = PROGRESS_DOTS.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "unlock-phase",
    )
    val width = PROGRESS_PITCH * (PROGRESS_DOTS - 1) + PROGRESS_DOT_DIAMETER
    Canvas(
        modifier = modifier
            .size(width = width, height = PROGRESS_DOT_DIAMETER)
            .semantics { contentDescription = "Unlocking" },
    ) {
        val radius = PROGRESS_DOT_DIAMETER.toPx() / 2f
        val pitch = PROGRESS_PITCH.toPx()
        val centerY = size.height / 2f
        for (i in 0 until PROGRESS_DOTS) {
            // Distance from the moving highlight, measured around the loop.
            val raw = ((i - phase) % PROGRESS_DOTS + PROGRESS_DOTS) % PROGRESS_DOTS
            val distance = minOf(raw, PROGRESS_DOTS - raw)
            val alpha = (1f - distance / 2.5f).coerceIn(0.15f, 1f)
            drawCircle(
                color = LenslyOnSurface.copy(alpha = alpha),
                radius = radius,
                center = Offset(radius + i * pitch, centerY),
            )
        }
    }
}
