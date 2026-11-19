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
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingPayload
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing
import app.lumio.pair.PairChain
import app.lumio.pair.PairImport
import app.lumio.ui.kit.LumioPrimaryButton
import app.lumio.ui.kit.LumioTag
import app.lumio.ui.scan.LumioScanScreen
import app.lumio.ui.send.ReviewAddress

/** The camera scan for a pairing code. [progress] shows for an animated code, and [status] says what the scan is doing. [complete] closes the ring. */
@Composable
fun PairScanScreen(progress: Float?, status: String, onText: (String) -> Unit, onCancel: () -> Unit, complete: Boolean = false) {
    LumioScanScreen(
        title = "Scan Pairing Code",
        progress = progress,
        statusText = status,
        onText = onText,
        onCancel = onCancel,
        complete = complete,
    )
}

/**
 * The review of a scanned pairing, before anything is saved: the wallet, each Ethereum account with the name, label and group Lensly gave
 * it, and each Bitcoin account with its network, Script Type and first address. [alreadyPaired] means saving replaces what Lumio holds for
 * the wallet. [error] is shown when a save failed.
 */
@Composable
fun PairReviewScreen(
    pairing: PairingPayload,
    alreadyPaired: Boolean,
    error: String?,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val accounts = pairing.apps.firstOrNull { it.app == PairImport.ETHEREUM }?.accounts.orEmpty()
    val bitcoin = PairImport.bitcoinAccountsOf(pairing)
    val bitcoinNames = pairing.apps.firstOrNull { it.app == PairImport.BITCOIN }?.accounts.orEmpty().associateBy { it.key }
    LenslyScreen(
        title = "Review Pairing",
        onBack = onCancel,
        bottomAction = { LumioPrimaryButton(text = if (alreadyPaired) "Update" else "Save", onClick = onSave) },
    ) {
        if (alreadyPaired) {
            InlineBanner(
                message = "This wallet is already paired. Saving makes its names, labels, groups and accounts match Lensly.",
                tone = BannerTone.Info,
            )
            Spacer(Modifier.height(LenslySpacing.md))
        }
        ReviewHeader(title = pairing.name, fingerprint = pairing.fingerprint)
        if (accounts.isNotEmpty()) EthereumReview(addresses = PairImport.ethereumAddressesOf(pairing), accounts = accounts)
        if (bitcoin.isNotEmpty()) BitcoinReview(accounts = bitcoin, names = bitcoinNames)
        val solana = PairImport.solanaPublicKeysOf(pairing)
        if (solana.isNotEmpty()) SolanaReview(keys = solana)
        val algorand = PairImport.algorandPublicKeysOf(pairing)
        if (algorand.isNotEmpty()) AlgorandReview(keys = algorand)
        error?.let {
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(message = it, tone = BannerTone.Error)
        }
    }
}

@Composable
private fun EthereumReview(addresses: List<String>, accounts: List<PairingAccount>) {
    Spacer(Modifier.height(LenslySpacing.md))
    LumioTag(text = PairLabels.chainName(PairChain.EVM))
    ReviewField("Network", "All EVM Networks")
    ReviewField("Accounts", "${accounts.size}")
    accounts.sortedBy { it.key }.forEach { account -> EthereumAccountReview(address = addresses[account.key], account = account) }
}

@Composable
private fun EthereumAccountReview(address: String, account: PairingAccount) {
    val index = account.key
    Column(modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.md)) {
        Text(
            text = account.name ?: "Account $index",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (account.label != null || account.group != null) {
            Spacer(Modifier.height(LenslySpacing.xxs))
            Row {
                account.label?.let { LumioTag(text = it) }
                if (account.label != null && account.group != null) Spacer(Modifier.width(LenslySpacing.xs))
                account.group?.let { LumioTag(text = it) }
            }
        }
        ReviewAddress("First Address", address)
    }
}

/** The Solana accounts of a pairing, on devnet: each account's address, which is the base58 of its public key. */
@Composable
private fun SolanaReview(keys: List<ByteArray>) {
    Spacer(Modifier.height(LenslySpacing.md))
    LumioTag(text = PairLabels.chainName(PairChain.SOLANA))
    ReviewField("Network", "Devnet")
    ReviewField("Accounts", "${keys.size}")
    keys.forEachIndexed { index, key ->
        Column(modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.md)) {
            Text(text = "Account $index", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            ReviewAddress("Address", SolanaAccount.addressOf(key))
        }
    }
}

/** The Algorand accounts of a pairing: each account's address, which is the 58-character form of its public key. */
@Composable
private fun AlgorandReview(keys: List<ByteArray>) {
    Spacer(Modifier.height(LenslySpacing.md))
    LumioTag(text = PairLabels.chainName(PairChain.ALGORAND))
    ReviewField("Accounts", "${keys.size}")
    keys.forEachIndexed { index, key ->
        Column(modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.md)) {
            Text(text = "Account $index", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            ReviewAddress("Address", AlgorandAccount.addressOf(key))
        }
    }
}

/** The Bitcoin accounts of a pairing. [names] holds the name, label and group of each one, by its key's place in the pairing. */
@Composable
private fun BitcoinReview(accounts: List<BtcWatchOnlyAccount>, names: Map<Int, PairingAccount>) {
    Spacer(Modifier.height(LenslySpacing.md))
    LumioTag(text = PairLabels.chainName(PairChain.BITCOIN))
    ReviewField("Accounts", "${accounts.size}")
    accounts.forEachIndexed { key, account -> BitcoinAccountReview(account, names[key]) }
}

@Composable
private fun BitcoinAccountReview(account: BtcWatchOnlyAccount, named: PairingAccount?) {
    val address = remember(account) { account.receiveAddress(0) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = LenslySpacing.md)) {
        named?.name?.let { name ->
            Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(LenslySpacing.xxs))
        }
        Row {
            LumioTag(text = PairLabels.network(account.network))
            Spacer(Modifier.width(LenslySpacing.xs))
            LumioTag(text = PairLabels.scriptType(account.scriptType))
        }
        val label = named?.label
        val group = named?.group
        if (label != null || group != null) {
            Spacer(Modifier.height(LenslySpacing.xxs))
            Row {
                label?.let { LumioTag(text = it) }
                if (label != null && group != null) Spacer(Modifier.width(LenslySpacing.xs))
                group?.let { LumioTag(text = it) }
            }
        }
        ReviewField("Path", account.path.asString('\''))
        ReviewAddress("First Address", address)
    }
}

@Composable
private fun ReviewHeader(title: String, fingerprint: Long) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(LenslySpacing.xs))
    ReviewField("Master Fingerprint", PairLabels.fingerprint(fingerprint))
}

@Composable
private fun ReviewField(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.xxs)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
