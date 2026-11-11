package app.lensly.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import app.lensly.app.autoAccountName
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.BulkPolicy
import app.lensly.core.wallet.formatAccounts
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.NumericKeypad
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.DotText
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillSize
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.wallet.WalletScaffold

/**
 * The bulk screen of one wallet. The owner types a Count on the [NumericKeypad] or taps a preset. A Count above the
 * configured [cap] (the owner's Bulk Cap), or below 1, is refused in an error banner, and GENERATE stays off for it.
 * While a run derives, the screen shows its progress and takes no input. [existingAccounts] is the wallet's account
 * count now, so the screen can name the accounts the run adds. [failure] says why the last run saved nothing, when it
 * failed.
 */
@Composable
fun BulkGenerateScreen(
    existingAccounts: Int,
    autoNameBase: String,
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
    val haptics = rememberKeyHaptics()
    val check = BulkPolicy.checkCount(digits.toIntOrNull(), cap.accounts)
    val count = (check as? BulkPolicy.CountCheck.Valid)?.count
    val message = failure ?: refusal(check)
    WalletScaffold(
        modifier = modifier,
        footer = {
            PillButton(
                text = if (count != null) "Generate $count" else "Generate",
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth(),
                accent = true,
                enabled = count != null && !running,
            )
            Spacer(Modifier.height(LenslySpacing.sm))
            NumericKeypad(onDigit = onDigit, onBackspace = onBackspace, enabled = !running)
        },
    ) {
        Row(
            modifier = Modifier.padding(top = LenslySpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalletPill(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.tapHaptic(haptics),
                style = WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
                enabled = !running,
            )
            Spacer(Modifier.weight(1f))
            WalletPill(
                text = "Lock",
                onClick = onLock,
                modifier = Modifier.tapHaptic(haptics),
                style = WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
            )
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletEyebrow("Bulk generate")
        Spacer(Modifier.height(LenslySpacing.xs))
        DotText(text = digits.ifEmpty { "—" }, fontSize = 48.sp)
        Spacer(Modifier.height(LenslySpacing.xxs))
        WalletEyebrow("Up to ${cap.label} per run · change in Settings")
        Spacer(Modifier.height(LenslySpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
            for (preset in BulkPolicy.presetsFor(cap.accounts)) {
                WalletPill(
                    text = preset.toString(),
                    onClick = { onPreset(preset) },
                    modifier = Modifier.tapHaptic(haptics),
                    style = if (digits == preset.toString()) WalletPillStyle.Secondary else WalletPillStyle.Outline,
                    size = WalletPillSize.Compact,
                    enabled = !running,
                )
            }
        }
        Spacer(Modifier.height(LenslySpacing.md))
        if (count != null) {
            WalletEyebrow(addsLabel(autoNameBase, BulkPolicy.indexRange(existingAccounts, count)))
            Spacer(Modifier.height(LenslySpacing.sm))
        }
        if (message != null) {
            InlineBanner(message = message, tone = BannerTone.Error)
            Spacer(Modifier.height(LenslySpacing.sm))
        }
        if (running && count != null) {
            LinearProgressIndicator(
                progress = { derived.toFloat() / count },
                modifier = Modifier.fillMaxWidth(),
                color = LenslyOnSurface,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(LenslySpacing.xs))
            WalletEyebrow("Deriving $derived of $count")
        }
    }
}

/** Names the Auto-Names a run adds, as the list shows them: "Adds Seed Wallet 3 to Seed Wallet 12", or one name alone. */
private fun addsLabel(baseName: String, range: IntRange): String {
    val first = autoAccountName(baseName, range.first)
    val last = autoAccountName(baseName, range.last)
    return if (range.first == range.last) "Adds $first" else "Adds $first to $last"
}

/** Why the typed Count cannot be generated. Null when it can, and when no Count has been typed yet. */
private fun refusal(check: BulkPolicy.CountCheck): String? = when (check) {
    BulkPolicy.CountCheck.Missing, is BulkPolicy.CountCheck.Valid -> null
    BulkPolicy.CountCheck.TooFew -> "At least 1 account"
    is BulkPolicy.CountCheck.TooMany -> "Above the Bulk Cap of ${formatAccounts(check.cap)}"
}
