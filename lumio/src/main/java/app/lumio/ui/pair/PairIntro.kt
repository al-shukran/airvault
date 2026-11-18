package app.lumio.ui.pair

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalLumenPalette
import app.lensly.ui.theme.LocalReducedMotion
import app.lumio.ui.kit.LumioPrimaryButton

/** One trip of a dot along the beam, from Lensly to Lumio, in milliseconds (section 2.1). */
private const val BEAM_MS = 1600

/** How many dots ride the beam. */
private const val DOTS = 3

/**
 * The Pair intro (section 2.1), the empty state of Wallets: dots travel a beam from Lensly to Lumio on a 1.6 s loop, three numbered steps say what to
 * do, and the one primary action starts the scan. With reduced motion the beam is still, its dots at fixed places.
 */
@Composable
fun PairIntro(onScan: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = LenslySpacing.md),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "Connect your vault",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(LenslySpacing.md))
            PairBeam(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(LenslySpacing.md))
            IntroStep(number = 1, text = "On Lensly, tap Pair, pick wallet")
            IntroStep(number = 2, text = "Aim here until the ring closes")
            IntroStep(number = 3, text = "Lumio reads balances. Keys stay in Lensly.")
        }
        Spacer(Modifier.height(LenslySpacing.md))
        LumioPrimaryButton(text = "Scan pairing code", onClick = onScan)
    }
}

/**
 * The beam: "Lensly", a line with dots riding it, and "Lumio". The dots' places come from the loop's phase, which is read in the draw phase only, so
 * the beam moves without recomposing. Reduced motion creates no loop.
 */
@Composable
private fun PairBeam(modifier: Modifier) {
    val phase: State<Float>? = if (LocalReducedMotion.current) {
        null
    } else {
        rememberInfiniteTransition(label = "pair beam").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(BEAM_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "pair beam phase",
        )
    }
    val gold = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("Lensly", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(modifier = Modifier.weight(1f).height(16.dp).padding(horizontal = LenslySpacing.xs)) {
            val y = size.height / 2f
            drawLine(color = track, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            for (i in 0 until DOTS) {
                val along = phase?.value?.let { (it + i.toFloat() / DOTS) % 1f } ?: ((i + 1f) / (DOTS + 1))
                drawCircle(color = gold, radius = 3.dp.toPx(), center = Offset(size.width * along, y))
            }
        }
        Text("Lumio", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One numbered step of the intro: the number in the accent ink, and the words beside it. */
@Composable
private fun IntroStep(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$number", style = MaterialTheme.typography.labelLarge, color = LocalLumenPalette.current.accentInk)
        Spacer(Modifier.width(LenslySpacing.md))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
