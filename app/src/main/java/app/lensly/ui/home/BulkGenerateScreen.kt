package app.lensly.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import app.lensly.app.SINGLE_KEY_NO_BITCOIN
import app.lensly.app.autoAccountName
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.BulkPolicy
import app.lensly.core.wallet.formatAccounts
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.NumericKeypad
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
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
 * What one Bulk Run makes. An EVM run makes accounts that span every EVM network. A Bitcoin run makes accounts of one Script Type on
 * one network, so the Script Type is fixed for the whole run.
 */
sealed interface BulkChain {
    /** Ethereum accounts. */
    data object Evm : BulkChain

    /** Bitcoin accounts of [scriptType] on [network]. */
    data class Bitcoin(val scriptType: ScriptType, val network: BtcNetwork) : BulkChain
}

/**
 * The bulk screen of one wallet. The owner first picks the chain in [chain], Ethereum or Bitcoin with its Script Type and network,
 * then types a Count on the [NumericKeypad] or taps a preset. A Count above the configured [cap] (the owner's Bulk Cap), or below 1,
 * is refused in an error banner, and GENERATE stays off for it. A Bitcoin run needs a seed, so [bitcoinAllowed] is false for a Single
 * Key wallet, and the screen then says why. While a run derives, the screen shows its progress and takes no input. [existingAccounts]
 * is the wallet's account count now, so the screen can name the accounts an EVM run adds. [failure] says why the last run saved
 * nothing, when it failed.
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
    chain: BulkChain = BulkChain.Evm,
    onChain: (BulkChain) -> Unit = {},
    bitcoinAllowed: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberKeyHaptics()
    val check = BulkPolicy.checkCount(digits.toIntOrNull(), cap.accounts)
    val count = (check as? BulkPolicy.CountCheck.Valid)?.count
    val message = failure ?: refusal(check)
    val canRun = chain is BulkChain.Evm || bitcoinAllowed
    WalletScaffold(
        modifier = modifier,
        footer = {
            PillButton(
                text = if (count != null) "Generate $count" else "Generate",
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth(),
                accent = true,
                enabled = count != null && !running && canRun,
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
        ChainChoice(chain = chain, onChain = onChain, running = running)
        if (chain is BulkChain.Bitcoin && !bitcoinAllowed) {
            Spacer(Modifier.height(LenslySpacing.sm))
            InlineBanner(message = SINGLE_KEY_NO_BITCOIN, tone = BannerTone.Warning)
        }
        Spacer(Modifier.height(LenslySpacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WalletEyebrow("Bulk generate")
            if (chain is BulkChain.Bitcoin && chain.network == BtcNetwork.TESTNET4) {
                Spacer(Modifier.width(LenslySpacing.xs))
                PillChip(text = "Testnet")
            }
        }
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
            WalletEyebrow(
                when (chain) {
                    BulkChain.Evm -> addsLabel(autoNameBase, BulkPolicy.indexRange(existingAccounts, count))
                    is BulkChain.Bitcoin -> bitcoinAddsLabel(chain.scriptType, count)
                },
            )
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

/** The Bitcoin run a tap on the Bitcoin pill starts with: Native Segwit on mainnet, the default for new Accounts. */
private val DEFAULT_BITCOIN = BulkChain.Bitcoin(ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET)

/**
 * The chain of the run, picked before the Count: Ethereum, or Bitcoin with its Script Type and network. The Script Type and the network
 * of a Bitcoin run are shown only while Bitcoin is chosen. Every pill is disabled while a run derives.
 */
@Composable
private fun ChainChoice(chain: BulkChain, onChain: (BulkChain) -> Unit, running: Boolean) {
    val haptics = rememberKeyHaptics()
    val bitcoin = chain as? BulkChain.Bitcoin ?: DEFAULT_BITCOIN
    Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
        WalletPill(
            text = "Ethereum",
            onClick = { onChain(BulkChain.Evm) },
            modifier = Modifier.tapHaptic(haptics),
            style = if (chain == BulkChain.Evm) WalletPillStyle.Secondary else WalletPillStyle.Outline,
            size = WalletPillSize.Compact,
            enabled = !running,
        )
        WalletPill(
            text = "Bitcoin",
            onClick = { onChain(bitcoin) },
            modifier = Modifier.tapHaptic(haptics),
            style = if (chain is BulkChain.Bitcoin) WalletPillStyle.Secondary else WalletPillStyle.Outline,
            size = WalletPillSize.Compact,
            enabled = !running,
        )
    }
    if (chain !is BulkChain.Bitcoin) return
    Spacer(Modifier.height(LenslySpacing.xs))
    Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
        ScriptType.entries.forEach { script ->
            WalletPill(
                text = pillLabel(script),
                onClick = { onChain(bitcoin.copy(scriptType = script)) },
                modifier = Modifier.tapHaptic(haptics),
                style = if (bitcoin.scriptType == script) WalletPillStyle.Secondary else WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
                enabled = !running,
            )
        }
    }
    Spacer(Modifier.height(LenslySpacing.xs))
    Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
        BtcNetwork.entries.forEach { network ->
            WalletPill(
                text = if (network == BtcNetwork.MAINNET) "Mainnet" else "Testnet",
                onClick = { onChain(bitcoin.copy(network = network)) },
                modifier = Modifier.tapHaptic(haptics),
                style = if (bitcoin.network == network) WalletPillStyle.Secondary else WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
                enabled = !running,
            )
        }
    }
}

/** The short name of a Script Type, for its pill. */
private fun pillLabel(scriptType: ScriptType): String = when (scriptType) {
    ScriptType.LEGACY -> "Legacy"
    ScriptType.NESTED_SEGWIT -> "Nested"
    ScriptType.NATIVE_SEGWIT -> "Native"
    ScriptType.TAPROOT -> "Taproot"
}

/** The line that names what a Bitcoin run adds: its Script Type in full, and the count of accounts. */
private fun bitcoinAddsLabel(scriptType: ScriptType, count: Int): String {
    val name = when (scriptType) {
        ScriptType.LEGACY -> "Legacy"
        ScriptType.NESTED_SEGWIT -> "Nested Segwit"
        ScriptType.NATIVE_SEGWIT -> "Native Segwit"
        ScriptType.TAPROOT -> "Taproot"
    }
    return if (count == 1) "Adds 1 $name account" else "Adds $count $name accounts"
}

/** Why the typed Count cannot be generated. Null when it can, and when no Count has been typed yet. */
private fun refusal(check: BulkPolicy.CountCheck): String? = when (check) {
    BulkPolicy.CountCheck.Missing, is BulkPolicy.CountCheck.Valid -> null
    BulkPolicy.CountCheck.TooFew -> "At least 1 account"
    is BulkPolicy.CountCheck.TooMany -> "Above the Bulk Cap of ${formatAccounts(check.cap)}"
}
