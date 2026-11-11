package app.lensly.ui.flow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.app.export.AccountExport
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.home.groupedAddress
import app.lensly.ui.qr.ShowQrScreen
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.SpaceMonoFamily
import app.lensly.ui.wallet.WalletEyebrow

private const val EXPORT_ACCOUNT_TITLE = "Export account"

/** What an account export shows: the account's address, and its derivation path, which a Single Key does not have. */
internal data class AccountExportView(val address: String, val derivationPath: String?)

/**
 * EXPORT for one account: its address as one plain QR code, with the full address in groups of four under it. A
 * Mnemonic account also shows its derivation path. A Single Key has none, so only its address shows. Public data only,
 * and the code is the address exactly as shown, so its letter case stays.
 */
@Composable
internal fun AccountExportScreen(
    address: String,
    derivationPath: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ShowQrScreen(
        title = EXPORT_ACCOUNT_TITLE,
        subtitle = if (derivationPath == null) {
            "The address of this single key. Public data only."
        } else {
            "The address of this account. Public data only."
        },
        parts = AccountExport.qrParts(address),
        onDone = onDone,
        modifier = modifier,
        subtitleLeading = { AddressIcon(size = 16.dp) },
        onBack = onBack,
        details = { AccountAddressDetails(address = address, derivationPath = derivationPath) },
        keepCase = true,
    )
}

/** The full address in groups of four, and the derivation path under a label when there is one. */
@Composable
private fun AccountAddressDetails(address: String, derivationPath: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = groupedAddress(address),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
            color = LenslyOnSurface,
            textAlign = TextAlign.Center,
        )
        if (derivationPath != null) {
            Spacer(Modifier.height(LenslySpacing.sm))
            WalletEyebrow("Derivation path")
            Spacer(Modifier.height(LenslySpacing.xxs))
            Text(
                text = derivationPath,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
