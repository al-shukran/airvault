package app.lensly.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.camera.EntryGesture
import app.lensly.ui.camera.EntryGesturePad
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SettingsSection
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.StepHeader

/** A change of the gesture has two steps: the App Password, then the practice. */
private const val CHANGE_STEP_COUNT = 2

/**
 * The Hidden Entry Gesture: one card for each of the four gestures, each with its demo, icon, title and description. The
 * current one is selected and cannot be chosen again. Choosing another starts the change, which asks for the App Password and
 * then has the owner practise the new gesture.
 */
@Composable
fun EntryGestureScreen(
    current: EntryGesture,
    onChoose: (EntryGesture) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = "Hidden entry gesture", modifier = modifier, onBack = onBack) {
        Text(
            text = "The gesture opens the hidden area from the camera. To change it, enter your Application Password, then perform " +
                "the new gesture once. It is saved only after it works.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = LenslySpacing.md),
        )
        SettingsSection("Choose one") {
            Column(verticalArrangement = Arrangement.spacedBy(LenslySpacing.sm)) {
                for (gesture in EntryGesture.entries) {
                    GestureOption(
                        gesture = gesture,
                        current = gesture == current,
                        onChoose = { onChoose(gesture) },
                    )
                }
            }
        }
        Spacer(Modifier.height(LenslySpacing.xl))
    }
}

/**
 * One gesture as a selectable card: its demo, then its icon, title and description. The current gesture is selected, with a
 * mark, and is not offered again. Choosing any other calls [onChoose].
 */
@Composable
private fun GestureOption(gesture: EntryGesture, current: Boolean, onChoose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pressFeedback(interaction, enabled = true)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, if (current) colors.primary else colors.outline, shape)
            .tapHaptic(haptics, enabled = !current)
            .selectable(
                selected = current,
                enabled = !current,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onChoose,
            )
            .padding(LenslySpacing.md),
        verticalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
    ) {
        GestureDemo(gesture = gesture, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = gesture.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(LenslySpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = gesture.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = gesture.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            if (current) {
                Spacer(Modifier.width(LenslySpacing.sm))
                PillChip(text = "Current")
            }
        }
    }
}

/** The icon that stands for the gesture: a tap on the viewfinder, a swipe, a zoom, or a camera switch. */
private fun EntryGesture.icon(): ImageVector = when (this) {
    EntryGesture.DOUBLE_TAP_HOLD -> Icons.Filled.Place
    EntryGesture.THUMBNAIL_HOLD_SWIPE -> Icons.AutoMirrored.Filled.ArrowForward
    EntryGesture.PINCH_TRIPLE_TAP -> Icons.Filled.Add
    EntryGesture.SWITCH_THEN_SHUTTER_HOLD -> Icons.Filled.Refresh
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
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = "Hidden entry gesture", modifier = modifier, onBack = onBack, scrollable = false) {
        StepHeader(step = 2, count = CHANGE_STEP_COUNT)
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
