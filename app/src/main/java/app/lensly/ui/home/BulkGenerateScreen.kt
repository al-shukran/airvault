package app.lensly.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.airvault.chains.algorand.algorandManifest
import app.airvault.chains.bitcoin.bitcoinManifest
import app.airvault.chains.evm.evmManifest
import app.airvault.chains.solana.solanaManifest
import app.airvault.core.model.ChainManifest
import app.airvault.core.model.NetworkSpec
import app.lensly.app.SINGLE_KEY_NO_BITCOIN
import app.lensly.app.WalletStore
import app.lensly.app.autoAccountName
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.BulkPolicy
import app.lensly.core.wallet.formatAccounts
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.ChoiceDialogRow
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslySegmentedControl
import app.lensly.ui.components.NumberField
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletScaffold

/**
 * The chain a Bulk Run makes accounts on, as the chain registry declares it (spec 2.1). [app] is the chain app's manifest: it names the app
 * and lists its networks. [network] is the network the run's keys come from. Keys differ between an app's networks only by coin type
 * (Bitcoin's mainnet is 0 and its testnet is 1, spec 2.2), so the screen offers a choice of network only where the coin types differ.
 * [scriptType] is the Bitcoin Script Type, which picks the purpose of the account path. It is null for an app with one kind of account.
 */
data class BulkTarget(
    val app: ChainManifest,
    val network: NetworkSpec,
    val scriptType: ScriptType? = null,
) {
    /** Every run but Ethereum's needs a seed. */
    val needsSeed: Boolean get() = app.id != evmManifest.id
}

/**
 * The bulk screen of one wallet. The screen that opened it chose the App, so the App is the subtitle, not a choice. A Bitcoin run takes
 * its network from a segmented control, Mainnet or Testnet, and its Script Type from a row that opens a list of the four types. The
 * owner types the Count into a number field with the system number keyboard. A Count above the [cap] (the owner's Bulk Cap), or below 1,
 * is refused under the field, and GENERATE stays off for it. Every run but Ethereum's needs a seed, so [bitcoinAllowed] is false for a
 * Single Key wallet, and a Bitcoin run then says why. While a run derives, the screen shows its progress and takes no input. [existingAccounts] is
 * the wallet's account count now, so the screen can name the accounts a run adds. [failure] says why the last run saved nothing, when
 * it failed.
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
    chain: BulkTarget = DEFAULT_EVM,
    onChain: (BulkTarget) -> Unit = {},
    bitcoinAllowed: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val check = BulkPolicy.checkTyped(digits, cap.accounts)
    val count = (check as? BulkPolicy.CountCheck.Valid)?.count
    val canRun = !chain.needsSeed || bitcoinAllowed
    val scriptType = chain.scriptType
    val choosesNetwork = chain.app.networks.distinctBy { it.slip44 }.size > 1
    WalletScaffold(
        modifier = modifier,
        topBar = { DetailTopBar(title = "Bulk Generate", onBack = { if (!running) onBack() }) },
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
        if (choosesNetwork) {
            Spacer(Modifier.height(LenslySpacing.sm))
            LenslySegmentedControl(
                options = chain.app.networks,
                selected = chain.network,
                label = { network -> if (network.testnet) "Testnet" else "Mainnet" },
                onSelect = { network -> if (!running) onChain(chain.copy(network = network)) },
            )
        }
        if (scriptType != null && !bitcoinAllowed) {
            Spacer(Modifier.height(LenslySpacing.sm))
            InlineBanner(message = SINGLE_KEY_NO_BITCOIN, tone = BannerTone.Warning)
        }
        if (scriptType != null) {
            ChoiceDialogRow(
                label = "Script Type",
                value = scriptName(scriptType),
                options = BITCOIN_SCRIPT_TYPES,
                selected = scriptType,
                optionLabel = ::scriptName,
                onSelect = { chosen -> onChain(chain.copy(scriptType = chosen)) },
                description = "The address type every new account gets.",
                enabled = !running,
            )
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
            text = "Up to ${cap.label} per run. Change the Bulk Cap in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        if (count != null) {
            Text(
                text = if (scriptType != null) {
                    bitcoinAddsLabel(scriptType, count)
                } else {
                    addsLabel(autoNameBase, BulkPolicy.indexRange(existingAccounts, count))
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
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
                color = MaterialTheme.colorScheme.onSurface,
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

/** The GENERATE label names the Count, such as "Generate 20 Accounts" or "Generate 1 Account". */
private fun generateLabel(count: Int?): String = when (count) {
    null -> "Generate"
    1 -> "Generate 1 Account"
    else -> "Generate $count Accounts"
}

/**
 * The App, as the subtitle: Ethereum on every EVM network, or the name of the chain app. The network and Script Type of a run are chosen
 * below it.
 */
private fun subtitle(chain: BulkTarget): String =
    if (chain.app.id == evmManifest.id) "Ethereum · All Networks" else chain.app.displayName

/** Names the Auto-Names a run adds, as the list shows them: "Adds Seed wallet 3 to Seed wallet 22", or one name alone. */
private fun addsLabel(baseName: String, range: IntRange): String {
    val first = autoAccountName(baseName, range.first)
    val last = autoAccountName(baseName, range.last)
    return if (range.first == range.last) "Adds $first" else "Adds $first to $last"
}

/** The run of [app] that a bulk screen starts with: its first mainnet network, and [scriptType] for an app that has Script Types. */
private fun startingRun(app: ChainManifest, scriptType: ScriptType? = null): BulkTarget =
    BulkTarget(app, app.networks.first { !it.testnet }, scriptType)

/** The run each bulk screen starts with. A Bitcoin run starts on Native Segwit, the default for new Accounts. */
internal val DEFAULT_EVM: BulkTarget = startingRun(evmManifest)
internal val DEFAULT_BITCOIN: BulkTarget = startingRun(bitcoinManifest, ScriptType.NATIVE_SEGWIT)
internal val DEFAULT_SOLANA: BulkTarget = startingRun(solanaManifest)
internal val DEFAULT_ALGORAND: BulkTarget = startingRun(algorandManifest)

/** The Script Types a Bitcoin run can make, in the order the list shows them. Native Segwit is the default. */
private val BITCOIN_SCRIPT_TYPES = listOf(
    ScriptType.NATIVE_SEGWIT,
    ScriptType.TAPROOT,
    ScriptType.NESTED_SEGWIT,
    ScriptType.LEGACY,
)

/**
 * Creates the accounts of [chain] for wallet [id]: [count] Ethereum accounts, or [count] Bitcoin accounts of the chain's Script Type on
 * its network, each numbered after the last one of that kind the wallet holds. [cap] is the Bulk Cap the run is checked against.
 * [onProgress] receives the number of accounts derived so far. Returns false when the wallet changed while the run derived, and nothing
 * was saved then.
 */
internal fun runBulk(
    store: WalletStore,
    id: String,
    chain: BulkTarget,
    count: Int,
    cap: Int,
    onProgress: (Int) -> Unit,
): Boolean = when (chain.app.id) {
    evmManifest.id -> store.addAccounts(id, count, cap, onProgress) != null
    solanaManifest.id -> store.addSolanaAccounts(id, count, cap, onProgress) != null
    algorandManifest.id -> store.addAlgorandAccounts(id, count, cap, onProgress) != null
    bitcoinManifest.id -> store.addBitcoinAccounts(
        id,
        requireNotNull(chain.scriptType) { "A Bitcoin run has a Script Type" },
        bitcoinNetworkOf(chain.network),
        count,
        cap,
        onProgress,
    ) != null
    else -> error("No bulk run for ${chain.app.id}")
}

/** The Bitcoin network that a registry network stands for. Its coin type is the one the account path uses, so the two agree (spec 2.2). */
private fun bitcoinNetworkOf(network: NetworkSpec): BtcNetwork = BtcNetwork.entries.single { it.coinType == network.slip44 }

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
