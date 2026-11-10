package app.lensly.ui.wallet

import androidx.compose.foundation.layout.Arrangement
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
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.SecureHexKeyboard
import app.lensly.ui.gate.PasswordField
import app.lensly.ui.home.groupedAddress

/** The two screens of a private key import. The address is shown only once the typed key has been checked. */
enum class KeyImportStep { Enter, Address }

/**
 * UI state for [KeyEntryScreen]. Like the gate screens, it holds only the character count. [error] is the reason the
 * last DONE was refused, or null. The caller owns the typed characters.
 */
data class KeyEntryState(
    val length: Int = 0,
    val error: String? = null,
)

/**
 * Typing a private key. The owner types 64 hex digits, with or without 0x, on [SecureHexKeyboard]. The field shows only
 * how many characters are typed, as the App Password does, so the key is not read off the screen. The screen warns that
 * a lost key is lost funds, because Lensly cannot make it again. DONE checks the key, and [KeyEntryState.error] says why
 * a key was refused.
 */
@Composable
fun KeyEntryScreen(
    state: KeyEntryState,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WalletScaffold(
        modifier = modifier,
        scrollable = false,
        footer = {
            SecureHexKeyboard(
                onKey = onKey,
                onBackspace = onBackspace,
                onDone = onDone,
            )
        },
    ) {
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalletEyebrow("Import")
            Spacer(Modifier.width(12.dp))
            PillChip(text = "EVM")
        }
        Spacer(Modifier.height(12.dp))
        // 48sp keeps "PRIVATE KEY" on one line on a 720dp-wide phone, as PassphraseScreen does for its headline.
        DotText(text = "PRIVATE KEY", fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        WalletWarningChip(text = "A lost key is lost funds")
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Lensly cannot make this key again. Type its 64 hex digits, with or without 0x.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        WalletEyebrow("${state.length} characters")
        Spacer(Modifier.height(8.dp))
        PasswordField(length = state.length)
        Spacer(Modifier.height(12.dp))
        WalletError(message = state.error)
        Spacer(Modifier.weight(1f))
    }
}

/**
 * The address step of a private key import. It shows the address that the key controls, in full, so the owner can check
 * it before the key is kept. The address is the same on every EVM network. Back returns to the key entry.
 */
@Composable
fun KeyAddressScreen(
    address: String,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WalletScaffold(
        modifier = modifier,
        footer = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WalletPill(
                    text = "Confirm address",
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    style = WalletPillStyle.Primary,
                )
                WalletPill(
                    text = "Back",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    style = WalletPillStyle.Outline,
                )
            }
        },
    ) {
        Spacer(Modifier.height(24.dp))
        WalletEyebrow("Check the address")
        Spacer(Modifier.height(12.dp))
        DotHeadline(text = "ADDRESS")
        Spacer(Modifier.height(16.dp))
        PillChip(text = "EVM")
        Spacer(Modifier.height(20.dp))
        Text(
            text = "This key controls the address below. Check it is the one you expect before you continue.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AddressIcon(size = 16.dp)
            Spacer(Modifier.width(8.dp))
            WalletEyebrow("Account 1")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = groupedAddress(address),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
