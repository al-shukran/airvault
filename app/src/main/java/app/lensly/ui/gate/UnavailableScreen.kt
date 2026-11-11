package app.lensly.ui.gate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslySpacing

/**
 * Neutral message for when the vault cannot be opened on this device. It does not say why, so it gives nothing away
 * about the vault. The only action is Close, which locks the vault and returns to the camera.
 */
@Composable
fun UnavailableScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** A reason the build itself refuses, such as a backup-enabled build. Null for a device that cannot hold a vault. */
    detail: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = LenslySpacing.md),
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = "OFFLINE VAULT",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        DotHeadline(text = "NOT AVAILABLE")
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = "Lensly cannot open on this device.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        detail?.let { reason ->
            Spacer(Modifier.height(LenslySpacing.sm))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.weight(1f))

        PillButton(
            text = "Close",
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            accent = true,
        )
        Spacer(Modifier.height(LenslySpacing.md))
    }
}
