package app.lensly.ui.home

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.registry.update.RegistryPackage
import app.lensly.core.registry.update.RegistryUpdateException
import app.lensly.ui.components.ChainIcon
import app.lensly.ui.components.TokenIcon
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.wallet.DotText
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillSize
import app.lensly.ui.wallet.WalletPillStyle
import app.lensly.ui.wallet.WalletScaffold

/** What the import screen shows: nothing chosen yet, a package being checked, an accepted package, or a refusal. */
sealed interface ImportState {
    data object Idle : ImportState
    data object Checking : ImportState
    data class Installed(val sequence: Long, val networks: List<NetworkEntry>, val tokens: List<TokenEntry>) : ImportState
    data class Refused(val reason: String) : ImportState
}

/** The state for an accepted package: its sequence and the networks and tokens it added. */
fun importedState(pkg: RegistryPackage): ImportState.Installed = ImportState.Installed(
    sequence = pkg.sequence,
    networks = pkg.networks,
    tokens = pkg.tokens,
)

/** The reason to show for a refused package. Anything that is not a [RegistryUpdateException] is a read or storage error. */
fun refusalReason(error: Throwable): String = when (error) {
    // A tampered file and a file signed with another key fail the same check, so one message covers both.
    is RegistryUpdateException.BadSignature ->
        "Signature check failed. The file was altered, or it was not signed with the release key."
    is RegistryUpdateException.NotNewer ->
        "Sequence ${error.offered} is not newer than the installed sequence ${error.installed}. " +
            "An older package cannot roll the catalog back."
    is RegistryUpdateException.Conflict -> "Conflict: ${error.message}."
    is RegistryUpdateException.InvalidEntry -> "Invalid entry: ${error.message}."
    is RegistryUpdateException.UnsupportedFormat -> "Format version ${error.version} is not supported by this app."
    is RegistryUpdateException.Malformed -> "Not a registry package: ${error.message}."
    else -> "The package could not be read or stored."
}

/**
 * Installs a signed registry package from removable storage. The owner picks the file with [onChooseFile]; the result
 * is shown here, either what the package added or the reason it was refused. Nothing needs a network connection.
 */
@Composable
fun RegistryImportScreen(
    state: ImportState,
    onChooseFile: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WalletScaffold(
        modifier = modifier,
        footer = {
            WalletPill(
                text = "Choose file",
                onClick = onChooseFile,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                style = WalletPillStyle.Primary,
                enabled = state !is ImportState.Checking,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalletPill(
                text = "Back",
                onClick = onBack,
                style = WalletPillStyle.Outline,
                size = WalletPillSize.Compact,
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
        WalletEyebrow("Offline catalog")
        Spacer(Modifier.height(8.dp))
        DotText(text = "CATALOG", fontSize = 40.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Insert removable storage with a signed package.",
            style = MaterialTheme.typography.bodyLarge,
            color = LenslyOnSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "A package adds networks and tokens. It cannot change or remove the built-in catalog.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        when (state) {
            ImportState.Idle -> Unit
            ImportState.Checking -> Text(
                text = "Checking the package…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is ImportState.Installed -> InstalledResult(state)
            is ImportState.Refused -> RefusedResult(state)
        }
    }
}

@Composable
private fun InstalledResult(state: ImportState.Installed) {
    Text(
        text = "INSTALLED · SEQUENCE ${state.sequence} · +${state.networks.size} NETWORKS +${state.tokens.size} TOKENS",
        style = MaterialTheme.typography.labelLarge,
        color = LenslyOnSurface,
    )
    Spacer(Modifier.height(12.dp))
    NetworkList(networks = state.networks)
    TokenList(tokens = state.tokens)
}

@Composable
private fun RefusedResult(state: ImportState.Refused) {
    WalletEyebrow("Refused")
    Spacer(Modifier.height(6.dp))
    Text(
        text = state.reason,
        style = MaterialTheme.typography.bodyLarge,
        color = LenslyAccent,
    )
}

@Composable
private fun NetworkList(networks: List<NetworkEntry>) {
    if (networks.isEmpty()) return
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        WalletEyebrow("Networks")
        networks.forEach { network ->
            LogoLine(text = "${network.name} · chain ${network.chainId}") {
                ChainIcon(network = network, size = 20.dp)
            }
        }
    }
}

@Composable
private fun TokenList(tokens: List<TokenEntry>) {
    if (tokens.isEmpty()) return
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        WalletEyebrow("Tokens")
        tokens.forEach { token ->
            LogoLine(text = "${token.symbol} · chain ${token.chainId}") {
                TokenIcon(token = token, size = 20.dp)
            }
        }
    }
}

/** One catalog entry: its logo, then its label. */
@Composable
private fun LogoLine(text: String, logo: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        logo()
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = LenslyOnSurface,
        )
    }
}
