package app.lensly.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.app.SINGLE_KEY_NO_BITCOIN
import app.lensly.app.autoAccountName
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.BulkPolicy
import app.lensly.core.wallet.formatAccounts
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslySegmentedControl
import app.lensly.ui.components.NumberField
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySpacing
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
 * The bulk screen of one wallet. The screen that opened it chose the App, so the App and its network are the subtitle, not choices.
 * A Bitcoin run takes its network from a segmented control, Mainnet or Testnet. The owner types the Count into a number field with the
 * system number keyboard. A Count above the [cap] (the owner's Bulk Cap), or below 1, is refused under the field, and GENERATE stays off
 * for it. A Bitcoin run needs a seed, so [bitcoinAllowed] is false for a Single Key wallet, and the screen then says why. While a run
 * derives, the screen shows its progress and takes no input. [existingAccounts] is the wallet's account count now, so the screen can
 * name the accounts a run adds. [failure] says why the last run saved nothing, when it failed.
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
    onDigits: (String) -> Unit,
    onGenerate: () -> Unit,
    onBack: () -> Unit,
    chain: BulkChain = BulkChain.Evm,
    onChain: (BulkChain) -> Unit = {},
    bitcoinAllowed: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val check = BulkPolicy.checkTyped(digits, cap.accounts)
    val count = (check as? BulkPolicy.CountCheck.Valid)?.count
    val canRun = chain is BulkChain.Evm || bitcoinAllowed
    WalletScaffold(
        modifier = modifier,
        topBar = { DetailTopBar(title = "Bulk generate", onBack = { if (!running) onBack() }) },
        footer = {
            PillButton(
                text = generateLabel(count),
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth(),
                accent = true,
                enabled = count != null && !running && canRun,
            )
        },
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = subtitle(chain),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (chain is BulkChain.Bitcoin) {
            Spacer(Modifier.height(LenslySpacing.sm))
            LenslySegmentedControl(
                options = BtcNetwork.entries,
                selected = chain.network,
                label = { network -> if (network == BtcNetwork.MAINNET) "Mainnet" else "Testnet" },
                onSelect = { network -> if (!running) onChain(chain.copy(network = network)) },
            )
        }
        if (chain is BulkChain.Bitcoin && !bitcoinAllowed) {
            Spacer(Modifier.height(LenslySpacing.sm))
            InlineBanner(message = SINGLE_KEY_NO_BITCOIN, tone = BannerTone.Warning)
        }
        Spacer(Modifier.height(LenslySpacing.md))
        NumberField(
            title = "Count",
            text = digits,
            onText = { typed -> if (!running) onDigits(typed) },
            suffix = "accounts",
            enabled = !running,
            error = refusal(check),
        )
        Text(
            text = "Up to ${cap.label} per run. Change the Bulk cap in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        if (count != null) {
            Text(
                text = when (chain) {
                    BulkChain.Evm -> addsLabel(autoNameBase, BulkPolicy.indexRange(existingAccounts, count))
                    is BulkChain.Bitcoin -> bitcoinAddsLabel(chain.scriptType, count)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = LenslyOnSurface,
            )
            Spacer(Modifier.height(LenslySpacing.sm))
        }
        if (failure != null) {
            InlineBanner(message = failure, tone = BannerTone.Error)
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
            Text(
                text = "Deriving $derived of $count",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The GENERATE label names the Count, such as "Generate 20 accounts" or "Generate 1 account". */
private fun generateLabel(count: Int?): String = when (count) {
    null -> "Generate"
    1 -> "Generate 1 account"
    else -> "Generate $count accounts"
}

/** The App and its network, as the subtitle: Ethereum on every EVM network, or Bitcoin with the Script Type a run makes. */
private fun subtitle(chain: BulkChain): String = when (chain) {
    BulkChain.Evm -> "Ethereum · all networks"
    is BulkChain.Bitcoin -> "Bitcoin · ${scriptName(chain.scriptType)}"
}

/** Names the Auto-Names a run adds, as the list shows them: "Adds Seed wallet 3 to Seed wallet 22", or one name alone. */
private fun addsLabel(baseName: String, range: IntRange): String {
    val first = autoAccountName(baseName, range.first)
    val last = autoAccountName(baseName, range.last)
    return if (range.first == range.last) "Adds $first" else "Adds $first to $last"
}

/** The Bitcoin run a screen starts with: Native Segwit on mainnet, the default for new Accounts. */
internal val DEFAULT_BITCOIN = BulkChain.Bitcoin(ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET)

/** The full name of a Script Type, as a sentence names it. */
private fun scriptName(scriptType: ScriptType): String = when (scriptType) {
    ScriptType.LEGACY -> "Legacy"
    ScriptType.NESTED_SEGWIT -> "Nested Segwit"
    ScriptType.NATIVE_SEGWIT -> "Native Segwit"
    ScriptType.TAPROOT -> "Taproot"
}

/** The line that names what a Bitcoin run adds: its Script Type in full, and the count of accounts. */
private fun bitcoinAddsLabel(scriptType: ScriptType, count: Int): String {
    val name = scriptName(scriptType)
    return if (count == 1) "Adds 1 $name account" else "Adds $count $name accounts"
}

/** Why the typed Count cannot be generated. Null when it can, and when no Count has been typed yet. */
private fun refusal(check: BulkPolicy.CountCheck): String? = when (check) {
    BulkPolicy.CountCheck.Missing, is BulkPolicy.CountCheck.Valid -> null
    BulkPolicy.CountCheck.TooFew -> "At least 1 account"
    is BulkPolicy.CountCheck.TooMany -> "Above the Bulk Cap of ${formatAccounts(check.cap)}"
}
