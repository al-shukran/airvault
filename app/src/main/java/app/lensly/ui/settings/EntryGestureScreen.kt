package app.lensly.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.camera.EntryGesturePad
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.StepHeader
import app.lensly.ui.wallet.WalletEyebrow

/** A change of the gesture has two steps: the App Password, then the practice. */
private const val CHANGE_STEP_COUNT = 2

/**
 * The Hidden Entry Gesture: one row for each of the four gestures. The current one is marked and cannot be chosen again.
 * Choosing another starts the change, which asks for the App Password and then has the owner practise the new gesture.
 */
@Composable
fun EntryGestureScreen(
    current: EntryGesture,
    onChoose: (EntryGesture) -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = "Hidden entry gesture", modifier = modifier, onBack = onBack) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalletEyebrow("Gesture", modifier = Modifier.weight(1f))
            PillButton(text = "Lock", onClick = onLock)
        }
        Text(
            text = "The gesture opens the hidden area from the camera. To change it, enter your App Password, then perform " +
                "the new gesture once. It is saved only after it works.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = LenslySpacing.md),
        )
        SettingsSection("Choose one") {
            for (gesture in EntryGesture.entries) {
                val isCurrent = gesture == current
                SettingsRow(
                    title = if (isCurrent) "${gesture.title} (current)" else gesture.title,
                    description = gesture.description,
                    onClick = if (isCurrent) null else ({ onChoose(gesture) }),
                )
            }
        }
        Spacer(Modifier.height(LenslySpacing.xl))
    }
}

/**
 * The practice step of a change: the owner performs [gesture] on a surface laid out like the camera. [onPerformed] runs once,
 * when the gesture works there. Nothing is saved until then.
 */
@Composable
fun EntryGesturePracticeStep(
    gesture: EntryGesture,
    onPerformed: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = "Hidden entry gesture", modifier = modifier, onBack = onBack, scrollable = false) {
        StepHeader(step = 2, onLock = onLock, count = CHANGE_STEP_COUNT)
        Spacer(Modifier.height(LenslySpacing.lg))
        DotHeadline(text = "Practice")
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = "Do this once: ${gesture.description} The gesture is saved only after it works.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        EntryGesturePad(gesture = gesture, onPerformed = onPerformed, modifier = Modifier.weight(1f))
    }
}
