package app.lensly.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.BulkPolicy
import app.lensly.core.wallet.formatAccounts
import app.lensly.ui.components.NumericKeypad
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.wallet.DotText
import app.lensly.ui.wallet.WalletError
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillSize
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.wallet.WalletScaffold

/**
 * The bulk screen of one wallet. The owner types a Count on the [NumericKeypad] or taps a preset. A Count above the
 * configured [cap] (the owner's Bulk Cap), or below 1, is refused on screen, and GENERATE stays off for it. While a run
 * derives, the screen shows its progress and takes no input. [existingAccounts] is the wallet's account count now, so
 * the screen can name the accounts the run adds. [failure] says why the last run saved nothing, when it failed.
 */
@Composable
fun BulkGenerateScreen(
    existingAccounts: Int,
    cap: BulkCap,
    digits: String,
    running: Boolean,
    derived: Int,
    failure: String?,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onPreset: (Int) -> Unit,
    onGenerate: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val check = BulkPolicy.checkCount(digits.toIntOrNull(), cap.accounts)
    val count = (check as? BulkPolicy.CountCheck.Valid)?.count
    WalletScaffold(
        modifier = modifier,
        footer = {
            WalletPill(
                text = if (count != null) "Generate $count" else "Generate",
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth(),
                style = WalletPillStyle.Primary,
                enabled = count != null && !running,
            )
            Spacer(Modifier.height(12.dp))
            NumericKeypad(onDigit = onDigit, onBackspace = onBackspace, enabled = !running)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WalletPill(
                text = "Back",
                onClick = onBack,
                style = WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
                enabled = !running,
            )
            Spacer(Modifier.weight(1f))
            WalletPill(
                text = "Lock",
                onClick = onLock,
                style = WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
            )
        }
        Spacer(Modifier.height(20.dp))
        WalletEyebrow("Bulk generate")
        Spacer(Modifier.height(8.dp))
        DotText(text = digits.ifEmpty { "—" }, fontSize = 48.sp)
        Spacer(Modifier.height(4.dp))
        WalletEyebrow("Accounts, up to ${cap.label} per run")
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in BulkPolicy.PRESETS) {
                WalletPill(
                    text = preset.toString(),
                    onClick = { onPreset(preset) },
                    style = if (digits == preset.toString()) WalletPillStyle.Secondary else WalletPillStyle.Outline,
                    size = WalletPillSize.Compact,
                    enabled = !running,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        if (count != null) {
            WalletEyebrow(addsLabel(BulkPolicy.indexRange(existingAccounts, count)))
        }
        WalletError(failure ?: refusal(check))
        if (running && count != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { derived.toFloat() / count },
                modifier = Modifier.fillMaxWidth(),
                color = LenslyOnSurface,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            WalletEyebrow("Deriving $derived of $count")
        }
    }
}

/** Names the accounts a run adds, numbered as the list shows them: "Account 4 to Account 13", or "Account 4" alone. */
private fun addsLabel(range: IntRange): String {
    val first = range.first + 1
    val last = range.last + 1
    return if (first == last) "Adds Account $first" else "Adds Account $first to Account $last"
}

/** Why the typed Count cannot be generated. Null when it can, and when no Count has been typed yet. */
private fun refusal(check: BulkPolicy.CountCheck): String? = when (check) {
    BulkPolicy.CountCheck.Missing, is BulkPolicy.CountCheck.Valid -> null
    BulkPolicy.CountCheck.TooFew -> "At least 1 account"
    is BulkPolicy.CountCheck.TooMany -> "Above the Bulk Cap of ${formatAccounts(check.cap)}"
}
