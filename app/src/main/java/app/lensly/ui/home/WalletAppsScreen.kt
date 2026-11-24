package app.lensly.ui.home

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.app.WalletApp
import app.lensly.ui.components.DetailTopBar
import app.lensly.ui.components.IslandDivider
import app.lensly.ui.components.LumioIsland
import app.lensly.ui.components.LumioRow
import app.lensly.ui.components.TextAction
import app.lensly.ui.wallet.WalletScaffold

/**
 * The Apps of one wallet. Each App is a row with its account count and one text action: Install for an App the wallet does not have, which
 * adds its account at once, and Uninstall for an installed one, which asks first. [accounts] holds the number of accounts of every App, and an
 * App with none is not installed.
 */
@Composable
fun WalletAppsScreen(
    accounts: Map<WalletApp, Int>,
    onBack: () -> Unit,
    onInstall: (WalletApp) -> Unit,
    onUninstall: (WalletApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    var uninstalling by remember { mutableStateOf<WalletApp?>(null) }
    WalletScaffold(
        modifier = modifier,
        topBar = { DetailTopBar(title = "Apps", onBack = onBack) },
    ) {
        LumioIsland {
            WalletApp.entries.forEachIndexed { index, app ->
                if (index > 0) IslandDivider()
                val count = accounts[app] ?: 0
                LumioRow(
                    title = app.label,
                    description = if (count > 0) "Installed · ${accountCountLabel(count)}" else "Not installed",
                    leading = { AppIcon(app = app, size = 32.dp) },
                    trailing = {
                        if (count > 0) {
                            TextAction(text = "Uninstall", onClick = { uninstalling = app })
                        } else {
                            TextAction(text = "Install", onClick = { onInstall(app) })
                        }
                    },
                )
            }
        }
    }
    uninstalling?.let { app ->
        UninstallDialog(
            app = app,
            count = accounts[app] ?: 0,
            onConfirm = {
                uninstalling = null
                onUninstall(app)
            },
            onDismiss = { uninstalling = null },
        )
    }
}

/** Asks before an App is uninstalled, and says what is lost and what Lumio keeps. */
@Composable
private fun UninstallDialog(app: WalletApp, count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Uninstall ${app.label}?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Text(
                text = "This removes ${accountCountLabel(count)} from this wallet. Their names, labels and groups are lost. " +
                    "Installing the app again creates the same addresses, and your recovery phrase is not touched. " +
                    "Lumio keeps its copy of these accounts until you pair again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = { TextAction(text = "Uninstall", onClick = onConfirm, destructive = true) },
        dismissButton = { TextAction(text = "Cancel", onClick = onDismiss) },
    )
}
