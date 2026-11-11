package app.lensly.ui.flow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.app.export.AccountExportView
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.home.groupedAddress
import app.lensly.ui.qr.AnimatedQr
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.SpaceMonoFamily
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillSize
import app.lensly.ui.wallet.WalletPillStyle

private const val PUBLIC_LABEL = "Public — safe to share"

/**
 * EXPORT of one account, or of a selection of accounts as one sequence. Each account is one step: its code, animated when its key
 * takes more than one frame, with its address and derivation path under it. With several accounts, "Account 2 of 5" sits above the
 * code and NEXT moves on, and DONE follows the last. CODE and TEXT switch between that code and a plain-text view of every field, in
 * full. Public data only, labelled as safe to share.
 */
@Composable
internal fun AccountExportScreen(
    accounts: List<AccountExportView>,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableStateOf(0) }
    var showText by rememberSaveable { mutableStateOf(false) }
    val last = accounts.lastIndex
    val current = accounts[step.coerceIn(0, last)]
    LenslyScreen(
        title = if (accounts.size == 1) "Export account" else "Export ${accounts.size} accounts",
        modifier = modifier,
        onBack = onBack,
        scrollable = false,
        bottomAction = {
            PillButton(
                text = if (step >= last) "Done" else "Next",
                onClick = { if (step >= last) onDone() else step++ },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AddressIcon(size = 16.dp)
            Spacer(Modifier.width(LenslySpacing.xs))
            Text(
                text = PUBLIC_LABEL,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (accounts.size > 1) WalletEyebrow("Account ${step + 1} of ${accounts.size}")
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
            WalletPill(
                text = "Code",
                onClick = { showText = false },
                style = if (showText) WalletPillStyle.Outline else WalletPillStyle.Primary,
                size = WalletPillSize.Compact,
            )
            WalletPill(
                text = "Text",
                onClick = { showText = true },
                style = if (showText) WalletPillStyle.Primary else WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
            )
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        if (showText) {
            AccountFields(current, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
            // A Single Key's code is its address in letter case, so it is encoded as written. A Mnemonic code is already uppercase UR.
            AnimatedQr(parts = current.parts, modifier = Modifier.fillMaxWidth(), keepCase = current.derivationPath == null)
            Spacer(Modifier.height(LenslySpacing.md))
            AccountAddressDetails(address = current.address, derivationPath = current.derivationPath)
            Spacer(Modifier.weight(1f))
        }
    }
}

/** The plain-text view: the full address, then every other field, each label over its value. Nothing is cut short. */
@Composable
private fun AccountFields(account: AccountExportView, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        FieldText(label = "Address", value = groupedAddress(account.address))
        account.fields.forEach { (label, value) -> FieldText(label = label, value = value) }
    }
}

@Composable
private fun FieldText(label: String, value: String) {
    Spacer(Modifier.height(LenslySpacing.sm))
    WalletEyebrow(label)
    Spacer(Modifier.height(LenslySpacing.xxs))
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
        color = LenslyOnSurface,
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
