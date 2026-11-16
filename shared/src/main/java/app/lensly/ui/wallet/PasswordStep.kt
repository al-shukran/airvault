package app.lensly.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.BottomSlot
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.DotProgress
import app.lensly.ui.components.KeyboardHost
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.gate.PasswordField
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceHighest

/**
 * The App Password step, shared by every flow that asks for the App Password again: Show Secret, Delete Wallet, Wipe, the Hidden Entry
 * Gesture and the settings changes. [title], [prompt] and [stepCount] say which action it belongs to.
 */
@Composable
fun PasswordStep(
    busy: Boolean,
    length: Int,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    title: String,
    prompt: String,
    stepCount: Int,
    step: Int = 1,
) {
    // The keyboard is up when the step opens. A tap outside the field or the system Back hides it, and a tap on the field brings it back.
    var focused by rememberSaveable { mutableStateOf(true) }
    KeyboardHost(focused = focused, onUnfocus = { focused = false }) {
        LenslyScreen(
            title = title,
            onBack = onBack,
            modifier = modifier,
            scrollable = false,
            bottomAction = {
                BottomSlot(visible = focused) {
                    SecureKeyboard(
                        onKey = onKey,
                        onBackspace = onBackspace,
                        onDone = onSubmit,
                        enabled = !busy,
                    )
                }
            },
        ) {
            StepHeader(step = step, count = stepCount)
            Spacer(Modifier.height(LenslySpacing.lg))
            DotHeadline(text = "PASSWORD")
            Spacer(Modifier.height(LenslySpacing.sm))
            Text(
                text = prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))

            PasswordField(length = length, onClick = { focused = true })
            Spacer(Modifier.height(LenslySpacing.sm))
            // Reserved height, so the keyboard does not move while the progress row comes and goes. A wrong password shows
            // nothing, as at the gate.
            Box(
                modifier = Modifier.fillMaxWidth().height(PROGRESS_SLOT_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DotProgress(description = "Checking password")
                        Spacer(Modifier.width(LenslySpacing.xs))
                        Text(
                            text = "CHECKING",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }
}

/** The step line at the top of a step: "Step N of [count]" and the progress bar underneath. */
@Composable
fun StepHeader(step: Int, count: Int) {
    WalletEyebrow("Step $step of $count")
    Spacer(Modifier.height(LenslySpacing.xs))
    StepSegments(count = count, filled = step)
}

/**
 * Progress through a flow with a known number of steps or pages: [count] short bars, the first [filled] of them lit.
 * TalkBack reads it as "[filled] of [count]".
 */
@Composable
fun StepSegments(count: Int, filled: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$filled of $count" },
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
    ) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(SEGMENT_HEIGHT)
                    .clip(LenslyPillShape)
                    .background(if (index < filled) LenslyOnSurface else LenslySurfaceHighest),
            )
        }
    }
}

private val PROGRESS_SLOT_HEIGHT = 24.dp
private val SEGMENT_HEIGHT = 3.dp
private val SEGMENT_GAP = 4.dp
