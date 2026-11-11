package app.lensly.ui.sign

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslySpacing

/**
 * Shown when the vault refuses to sign a request, for example on a chain mismatch or a malformed payload. [reason] says
 * why, in plain words. The red REFUSED headline and the error banner make the outcome clear at a glance, and BACK TO
 * WALLET leaves the flow.
 */
@Composable
fun SignRefusedScreen(
    reason: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(
        title = "Sign request",
        modifier = modifier,
        bottomAction = {
            PillButton(
                text = "Back to wallet",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = "REFUSED",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.displaySmall,
            color = LenslyError,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        InlineBanner(message = reason, tone = BannerTone.Error)
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = "Check the request on your online wallet, then start SIGN again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
