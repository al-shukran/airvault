package app.lensly.ui.sign

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.wallet.toHex
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslySpacing

private const val NO_WALLET = "No wallet in this vault matches this request"
private const val SEVERAL_WALLETS = "More than one wallet in this vault matches this request. Lensly will not guess which one signs."

/**
 * Shown when a scan of any wallet finds no wallet of the vault for the request, or more than one. It names the fingerprint,
 * path and address the request asks for, in full, so the owner can see what the online wallet wants. SCAN AGAIN starts the
 * scan over, and CANCEL leaves the flow.
 *
 * [several] is true when more than one wallet matched, so the banner says that instead.
 */
@Composable
fun NoMatchingWalletScreen(
    request: EthSignRequest,
    several: Boolean,
    onScanAgain: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(
        title = "Sign request",
        modifier = modifier,
        bottomAction = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
            ) {
                PillButton(text = "Cancel", onClick = onCancel, modifier = Modifier.weight(1f))
                PillButton(text = "Scan again", onClick = onScanAgain, modifier = Modifier.weight(1f))
            }
        },
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = "NO MATCH",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.displaySmall,
            color = LenslyError,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        InlineBanner(message = if (several) SEVERAL_WALLETS else NO_WALLET, tone = BannerTone.Error)
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = "Lensly signs only with a wallet in this vault. This request asks for:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FactRow(label = "Fingerprint") {
            MonoText(text = fingerprintText(request.sourceFingerprint))
        }
        FactRow(label = "Derivation path") {
            MonoText(text = pathText(request.derivationPath))
        }
        request.address?.let { address ->
            FactRow(label = "Address") {
                AddressText(address = "0x" + address.toHex())
            }
        }
    }
}
