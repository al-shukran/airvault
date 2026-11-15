package app.lumio.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslySpacing
import app.lumio.R

/**
 * Lumio's settings, in two sections. Bitcoin holds the Esplora server each Bitcoin network reads from, and Ethereum holds the EVM settings.
 * [bitcoin] and [ethereum] are the contents of the two sections, and each keeps its own state.
 */
@Composable
fun LumioSettingsScreen(
    onBack: () -> Unit,
    bitcoin: @Composable () -> Unit,
    ethereum: @Composable () -> Unit,
    onDataProviders: () -> Unit,
    onThirdPartyLicences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = "SETTINGS", modifier = modifier, onBack = onBack) {
        SettingsSection(title = "Bitcoin", content = bitcoin)
        SettingsSection(title = "Ethereum", content = ethereum)
        SettingsSection(title = stringResource(R.string.settings_providers_heading)) {
            Text(
                stringResource(R.string.settings_providers_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(LenslySpacing.md))
            PillButton(
                text = stringResource(R.string.settings_providers_open),
                onClick = onDataProviders,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SettingsSection(title = stringResource(R.string.settings_about_heading)) {
            Text(
                stringResource(R.string.settings_licences_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(LenslySpacing.md))
            PillButton(
                text = stringResource(R.string.settings_licences_open),
                onClick = onThirdPartyLicences,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(LenslySpacing.lg))
    Text(
        title,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(LenslySpacing.xs))
    content()
}

/**
 * The Bitcoin section: the Esplora server that each Bitcoin network reads from. [mainnet] and [testnet4] show the address in use, the default or
 * the owner's. [error] is shown when the last save was refused. [onSave] receives the two texts as the owner typed them, and [onReset] puts both
 * networks back on their defaults.
 */
@Composable
fun BitcoinServersSettings(
    mainnet: String,
    testnet4: String,
    error: String?,
    onSave: (mainnet: String, testnet4: String) -> Unit,
    onReset: () -> Unit,
) {
    var mainnetText by remember(mainnet) { mutableStateOf(mainnet) }
    var testnetText by remember(testnet4) { mutableStateOf(testnet4) }
    Text(
        "Lumio asks the server for each address it watches, so the server learns those addresses. Use an Esplora server you trust. " +
            "Each network has its own server, and an address must start with https://.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(LenslySpacing.md))
    OutlinedTextField(
        value = mainnetText,
        onValueChange = { mainnetText = it },
        label = { Text("Mainnet") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(LenslySpacing.sm))
    OutlinedTextField(
        value = testnetText,
        onValueChange = { testnetText = it },
        label = { Text("Testnet4") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let {
        Spacer(Modifier.height(LenslySpacing.md))
        InlineBanner(message = it, tone = BannerTone.Error)
    }
    Spacer(Modifier.height(LenslySpacing.md))
    PillButton(text = "Save", onClick = { onSave(mainnetText, testnetText) }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(LenslySpacing.sm))
    PillButton(text = "Reset to defaults", onClick = onReset)
}
