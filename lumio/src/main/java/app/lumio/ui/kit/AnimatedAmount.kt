package app.lumio.ui.kit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import app.lensly.ui.theme.LocalReducedMotion
import app.lensly.ui.theme.LumenMotion

/** What a hidden amount shows. It is the same at every size, so a hidden balance never shows how large it is. */
const val HIDDEN_AMOUNT = "••••"

/** The slide of a rolling digit: the spatialFast spring of section 3.1, for an offset. */
private val rollSpring = spring<IntOffset>(dampingRatio = 0.90f, stiffness = 700f)

/**
 * An amount, such as a balance, shown as [text], its exact formatted string (section 3.4). A hidden amount shows [HIDDEN_AMOUNT] and never moves.
 *
 * [countUp] is the count of the first sync: while it runs, each frame shows `countUp(fraction)`, as the fraction goes from 0 to 1 over
 * [LumenMotion.COUNT_UP], and then [text] takes its place. A later change of [text] rolls each digit like an odometer, and the digits keep their
 * width. A value shown on open passes no [countUp], so it never animates. Reduced motion shows [text] at once.
 */
@Composable
fun AnimatedAmount(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    hidden: Boolean = false,
    countUp: ((fraction: Float) -> String)? = null,
) {
    val reduced = LocalReducedMotion.current
    val counting = countUp != null && !reduced && !hidden
    val fraction = remember { Animatable(if (counting) 0f else 1f) }
    LaunchedEffect(counting) {
        if (counting) {
            fraction.snapTo(0f)
            fraction.animateTo(1f, tween(LumenMotion.COUNT_UP, easing = LumenMotion.Emphasized))
        }
    }
    when {
        hidden -> Text(text = HIDDEN_AMOUNT, modifier = modifier, style = style, color = color, maxLines = 1)
        reduced -> Text(text = text, modifier = modifier, style = style, color = color, maxLines = 1)
        countUp != null && counting && fraction.value < 1f ->
            Text(text = countUp(fraction.value), modifier = modifier, style = style, color = color, maxLines = 1)
        else -> Odometer(text = text, modifier = modifier, style = style, color = color)
    }
}

/** The amount with each digit in its own slot: a digit that changes rolls up or down, and the other characters stay put. */
@Composable
private fun Odometer(text: String, modifier: Modifier, style: TextStyle, color: Color) {
    Row(modifier = modifier.clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { index, char ->
            if (char.isDigit()) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically(rollSpring) { if (up) it else -it } + fadeIn(LumenMotion.effects))
                            .togetherWith(slideOutVertically(rollSpring) { if (up) -it else it } + fadeOut(LumenMotion.effects))
                    },
                    label = "amount digit $index",
                ) { digit ->
                    Text(text = digit.toString(), style = style, color = color, maxLines = 1)
                }
            } else {
                Text(text = char.toString(), style = style, color = color, maxLines = 1)
            }
        }
    }
}
