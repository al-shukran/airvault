package app.lumio.ui.gate

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.BottomSlot
import app.lensly.ui.components.KeyboardHost
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.gate.BIOMETRIC_OFF_NOTICE
import app.lensly.ui.navigation.UnlockUi
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lensly.ui.theme.LocalReducedMotion
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirIconButton
import app.lumio.ui.kit.ClearAirPasswordDisplay
import app.lumio.ui.kit.ClearAirSpark
import app.lumio.ui.kit.rejectOf
import app.lumio.ui.theme.LumioGutter

/**
 * The unlock page of Lumio's gate, in Clear Air. The App Password is typed on the in-app keyboard, so it never reaches the system keyboard,
 * and the field holds only a length. One status line says what the gate is doing: unlocking, the wait before the next try, the biometric
 * notice, or the tries left before the erase. No line names a factor. While a wait runs, typing stays on and Done is held.
 */
@Composable
fun LumioUnlockScreen(state: UnlockUi) {
    val palette = LocalClearAirPalette.current
    var focused by rememberSaveable { mutableStateOf(true) }
    val reduced = LocalReducedMotion.current
    val shake = rejectOf(failed = state.refused, shake = !reduced)
    KeyboardHost(focused = focused, onUnfocus = { focused = false }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = ClearAirSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClearAirIconButton(glyph = ClearAirGlyph.CLOSE, label = "Close", onClick = state.onClose)
            }
            Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = LumioGutter)) {
                ClearAirEyebrow(text = "App Password", top = 0.dp)
                Text(text = "Unlock Lumio", style = ClearAirType.titleXL, color = palette.ink)

                Spacer(Modifier.weight(1f))

                ClearAirPasswordDisplay(
                    length = state.length,
                    onClick = { focused = true },
                    modifier = shake.alpha(if (state.busy) DIMMED_ALPHA else 1f),
                    focused = focused && !state.busy,
                    placeholder = "Type your password",
                )
                Spacer(Modifier.height(ClearAirSpacing.sm))
                GateStatus(state)

                Spacer(Modifier.weight(1f))

                BottomSlot(visible = focused) {
                    SecureKeyboard(
                        onKey = state.onKey,
                        onBackspace = state.onBackspace,
                        onDone = state.onSubmit,
                        enabled = !state.busy,
                        doneHeld = state.waitMs != null,
                    )
                }
            }
        }
    }
}

/** The one line under the field. It cross-fades in 100 ms when its kind changes, and a wait's seconds count down in place. */
@Composable
private fun GateStatus(state: UnlockUi) {
    val palette = LocalClearAirPalette.current
    val kind = when {
        state.busy -> StatusKind.Unlocking
        state.waitMs != null -> StatusKind.Waiting
        state.biometricOff -> StatusKind.Biometric
        state.triesLeft != null -> StatusKind.Tries
        else -> StatusKind.None
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(STATUS_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(targetState = kind, animationSpec = tween(NOTICE_FADE_MS), label = "gate status") { shown ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (shown) {
                    StatusKind.Unlocking -> {
                        ClearAirSpark(progress = 1f, breathing = true, size = SPARK_SIZE)
                        Spacer(Modifier.width(ClearAirSpacing.xs))
                        Text(text = "Unlocking", style = ClearAirType.label, color = palette.ink2)
                    }
                    StatusKind.Waiting -> {
                        ClearAirIcon(glyph = ClearAirGlyph.INFO, color = palette.ink2, size = ICON_SIZE)
                        Spacer(Modifier.width(ClearAirSpacing.xs))
                        Text(
                            text = "Next try in ",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            style = ClearAirType.label,
                            color = palette.ink2,
                        )
                        Text(text = waitCountdown(state.waitMs ?: 0L), style = ClearAirType.time, color = palette.ink)
                    }
                    StatusKind.Biometric -> Text(
                        text = BIOMETRIC_OFF_NOTICE,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = ClearAirType.label,
                        color = palette.ink2,
                    )
                    StatusKind.Tries -> {
                        ClearAirIcon(glyph = ClearAirGlyph.ALERT, color = palette.rose, size = ICON_SIZE)
                        Spacer(Modifier.width(ClearAirSpacing.xs))
                        Text(
                            text = lumioEraseWarning(state.triesLeft ?: 0),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            style = ClearAirType.label,
                            color = palette.rose,
                        )
                    }
                    StatusKind.None -> Unit
                }
            }
        }
    }
}

private enum class StatusKind { Unlocking, Waiting, Biometric, Tries, None }

/** The warning as the last tries come, in Lumio's words: "2 tries left before Lumio is erased". */
internal fun lumioEraseWarning(triesLeft: Int): String =
    (if (triesLeft == 1) "1 try left" else "$triesLeft tries left") + " before Lumio is erased"

/**
 * The wait as the owner reads it: "38 s", "4 min 10 s", or "4 min" on a whole minute. The seconds round up, so the count reads 1 s until the wait
 * is over.
 */
internal fun waitCountdown(ms: Long): String {
    val seconds = (ms + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
    val minutes = seconds / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return when {
        minutes == 0L -> "$seconds s"
        rest == 0L -> "$minutes min"
        else -> "$minutes min $rest s"
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val NOTICE_FADE_MS = 100
private const val DIMMED_ALPHA = 0.4f
private val BAR_HEIGHT = 52.dp
private val STATUS_HEIGHT = 24.dp
private val ICON_SIZE = 16.dp
private val SPARK_SIZE = 9.dp
