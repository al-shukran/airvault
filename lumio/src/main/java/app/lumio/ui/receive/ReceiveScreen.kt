package app.lumio.ui.receive

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.AppTitle
import app.lensly.ui.components.BitcoinIcon
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.qr.AnimatedQr
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletScaffold

/** The test tag of the address on the receive page. */
const val RECEIVE_ADDRESS_TAG = "receive-address"

/**
 * The page that receives to one account: what the account is, its address as the owner can copy it, and the same address as a QR code for a sender
 * to scan. [bitcoin] picks the Bitcoin logo over the generic one.
 */
@Composable
fun ReceiveScreen(
    walletName: String,
    detail: String,
    address: String,
    bitcoin: Boolean,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WalletScaffold(modifier = modifier) {
        DetailTopBar(onBack = onBack, onLock = onLock)
        AppTitle(
            logo = { size -> if (bitcoin) BitcoinIcon(size = size) else AddressIcon(size = size) },
            name = "Receive",
            walletName = walletName,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(LenslySpacing.md))
        AnimatedQr(parts = listOf(address), modifier = Modifier.fillMaxWidth(), keepCase = true)
        Spacer(Modifier.height(LenslySpacing.md))
        AddressText(address = address, bitcoin = bitcoin, modifier = Modifier.testTag(RECEIVE_ADDRESS_TAG))
    }
}
