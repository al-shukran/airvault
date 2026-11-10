package app.lensly.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.theme.LenslyOnSurface

/**
 * The write-down of a Single Key import, the last step before the wallet is saved. The key is shown once, in groups of
 * four, so it can be copied onto a backup by hand. Lensly keeps an encrypted copy, but that copy is not a backup. The
 * owner confirms that the copy exists, and only then can CREATE WALLET be pressed.
 */
@Composable
fun KeyBackupScreen(
    hex: String,
    copied: Boolean,
    onCopiedChange: (Boolean) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WalletScaffold(
        modifier = modifier,
        footer = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CopiedCheck(checked = copied, onCheckedChange = onCopiedChange)
                WalletPill(
                    text = "Create wallet",
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth(),
                    style = WalletPillStyle.Primary,
                    enabled = copied,
                )
            }
        },
    ) {
        Spacer(Modifier.height(16.dp))
        WalletEyebrow("Single key. Shown once")
        Spacer(Modifier.height(8.dp))
        DotHeadline(text = "WRITE DOWN")
        Spacer(Modifier.height(12.dp))
        WalletWarningChip(text = "Never photograph")
        Spacer(Modifier.height(16.dp))
        Text(
            text = "This is the only time the key is shown. Lensly cannot make it again, and the encrypted copy on " +
                "this device is not a backup. Copy the key onto your backup now.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        HashLines(hex = hex)
        Spacer(Modifier.height(8.dp))
    }
}

/** The owner's explicit confirmation that the key has been copied. It is a checkbox for screen readers too. */
@Composable
private fun CopiedCheck(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val box = RoundedCornerShape(4.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, box)
                .background(if (checked) LenslyOnSurface else Color.Transparent, box),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "I copied the key onto my backup",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
