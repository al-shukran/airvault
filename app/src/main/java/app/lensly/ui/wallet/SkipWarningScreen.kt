package app.lensly.ui.wallet

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing

/**
 * Warning shown before the owner skips the ceremony. The wallet then trusts only the device's random
 * generator and is labelled DEVICE-GENERATED. Back is the primary choice; skipping is the red action.
 */
@Composable
fun SkipWarningScreen(
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(
        title = "Seed ceremony",
        modifier = modifier,
        bottomAction = {
            WalletPill(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
            )
            Spacer(Modifier.height(LenslySpacing.sm))
            WalletPill(
                text = "Skip anyway",
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Accent,
            )
        },
    ) {
        WalletEyebrow("Skip the ceremony")
        Spacer(Modifier.height(LenslySpacing.xs))
        DotHeadline(text = "NO MIXING")
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = "This wallet will trust only the device's random generator.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(LenslySpacing.lg))
        InlineBanner(
            title = "Device-generated",
            message = "The wallet is labelled DEVICE-GENERATED. Your own rolls and flips will not be used.",
            tone = BannerTone.Warning,
        )
    }
}
