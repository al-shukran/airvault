package app.lumio.ui.pair

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.wallet.AccountAddresses
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.qr.ScanScreen
import app.lensly.ui.theme.LenslySpacing
import app.lumio.pair.PairFamily
import app.lumio.pair.Pairing

/** The camera scan for a pairing code. [progress] shows for an animated code, and [status] says what the scan is doing. */
@Composable
fun PairScanScreen(progress: Float?, status: String, onText: (String) -> Unit, onCancel: () -> Unit) {
    ScanScreen(
        title = "PAIR FROM LENSLY",
        progress = progress,
        statusText = status,
        onText = onText,
        onCancel = onCancel,
    )
}

/**
 * The review of a scanned pairing, before anything is saved: what the code imports, with each Bitcoin account's network, Script Type and
 * first address. [alreadyPaired] means saving updates the wallet that is paired. [error] is shown when a save failed.
 */
@Composable
fun PairReviewScreen(
    pairing: Pairing,
    alreadyPaired: Boolean,
    error: String?,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    LenslyScreen(
        title = "REVIEW PAIRING",
        onBack = onCancel,
        bottomAction = {
            PillButton(text = if (alreadyPaired) "Update" else "Save", onClick = onSave, modifier = Modifier.fillMaxWidth())
        },
    ) {
        if (alreadyPaired) {
            InlineBanner(
                message = "This wallet is already paired. Saving replaces its public copy in Lumio.",
                tone = BannerTone.Info,
            )
            Spacer(Modifier.height(LenslySpacing.md))
        }
        when (pairing) {
            is Pairing.Evm -> EvmReview(pairing)
            is Pairing.Bitcoin -> BitcoinReview(pairing)
        }
        error?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

@Composable
private fun EvmReview(pairing: Pairing.Evm) {
    val address = remember(pairing) { AccountAddresses.addressAt(pairing.xpub, 0) }
    ReviewHeader(title = pairing.name ?: "Lensly", family = PairFamily.EVM, fingerprint = pairing.fingerprint)
    ReviewField("Network", "All EVM networks")
    ReviewField("Account", "m/44'/60'/0'")
    ReviewField("First address", address)
    ReviewField("Accounts", "1")
}

@Composable
private fun BitcoinReview(pairing: Pairing.Bitcoin) {
    ReviewHeader(title = "Bitcoin accounts", family = PairFamily.BITCOIN, fingerprint = pairing.fingerprint)
    ReviewField("Accounts", "${pairing.accounts.size}")
    pairing.accounts.forEach { account -> BitcoinAccountReview(account) }
}

@Composable
private fun BitcoinAccountReview(account: BtcWatchOnlyAccount) {
    val address = remember(account) { account.receiveAddress(0) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.md)) {
        Row {
            PillChip(text = PairLabels.network(account.network))
            Spacer(Modifier.width(LenslySpacing.xs))
            PillChip(text = PairLabels.scriptType(account.scriptType))
        }
        ReviewField("Path", account.path.asString('\''))
        ReviewField("First address", address)
    }
}

@Composable
private fun ReviewHeader(title: String, family: PairFamily, fingerprint: Long) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(LenslySpacing.xs))
    Row {
        PillChip(text = PairLabels.family(family))
    }
    ReviewField("Master fingerprint", PairLabels.fingerprint(fingerprint))
}

@Composable
private fun ReviewField(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xxs)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
