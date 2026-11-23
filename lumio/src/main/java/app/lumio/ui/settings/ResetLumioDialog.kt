package app.lumio.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirDialog
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirTextAction

/** The test tag of the capsule that confirms a reset. */
const val RESET_CONFIRM_TAG = "reset-confirm"

/**
 * The Reset Lumio dialog (J12): what a reset erases, and what it leaves alone. The checkbox says the owner understands that it cannot be undone. Until
 * the box is ticked, the rose capsule is disabled and says why. [onConfirm] runs when the capsule is pressed, and [onDismiss] on Cancel.
 */
@Composable
fun ResetLumioDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var understood by rememberSaveable { mutableStateOf(false) }
    val palette = LocalClearAirPalette.current
    ClearAirDialog(
        title = "Reset Lumio?",
        text = "Erases pairings, settings and cached balances on this phone. Lensly and your keys are not affected.",
        onDismiss = onDismiss,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ClearAirSpacing.touchTarget)
                .toggleable(value = understood, role = Role.Checkbox, onValueChange = { understood = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = understood, onCheckedChange = null)
            Spacer(Modifier.width(ClearAirSpacing.xs))
            Text(text = "I understand this can't be undone", style = ClearAirType.body, color = palette.ink)
        }
        ClearAirPrimaryButton(
            text = "Reset Lumio",
            onClick = onConfirm,
            enabled = understood,
            destructive = true,
            modifier = Modifier.testTag(RESET_CONFIRM_TAG),
        )
        if (!understood) {
            Text(text = "Tick the box above to continue", style = ClearAirType.label, color = palette.ink2)
        }
        ClearAirTextAction(text = "Cancel", onClick = onDismiss)
    }
}
