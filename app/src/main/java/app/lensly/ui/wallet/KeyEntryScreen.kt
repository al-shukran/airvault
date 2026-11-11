package app.lensly.ui.wallet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.BottomSlot
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.KeyboardHost
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.SecureHexKeyboard
import app.lensly.ui.gate.PasswordField
import app.lensly.ui.home.groupedAddress
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.SpaceMonoFamily

/** The two screens of a private key import. The address is shown only once the typed key has been checked. */
enum class KeyImportStep { Enter, Address }

/** Height of the line that shows why a key was refused. It is kept even when there is no refusal, so nothing moves. */
private val REFUSAL_HEIGHT = 72.dp

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
    // The keyboard is up when the screen opens. A tap outside the field or the system Back hides it, and a tap on the field brings it back.
    var focused by rememberSaveable { mutableStateOf(true) }
    KeyboardHost(focused = focused, onUnfocus = { focused = false }) {
        LenslyScreen(
            title = "Import key",
            modifier = modifier,
            scrollable = false,
        ) {
            Spacer(Modifier.height(LenslySpacing.lg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WalletEyebrow("Import")
                Spacer(Modifier.width(LenslySpacing.sm))
                PillChip(text = "EVM")
            }
            Spacer(Modifier.height(LenslySpacing.sm))
            // 48sp keeps "PRIVATE KEY" on one line on a 720dp-wide phone, as PassphraseScreen does for its headline.
            DotText(text = "PRIVATE KEY", fontSize = 48.sp)
            Spacer(Modifier.height(LenslySpacing.md))
            InlineBanner(
                message = "A lost key is lost funds. Lensly cannot make this key again.",
                tone = BannerTone.Warning,
            )
            Spacer(Modifier.height(LenslySpacing.md))
            Text(
                text = "Type its 64 hex digits, with or without 0x.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            WalletEyebrow("${state.length} characters")
            Spacer(Modifier.height(LenslySpacing.xs))
            PasswordField(length = state.length, onClick = { focused = true })
            Spacer(Modifier.height(LenslySpacing.sm))
            Box(
                modifier = Modifier.fillMaxWidth().height(REFUSAL_HEIGHT),
                contentAlignment = Alignment.TopCenter,
            ) {
                state.error?.let { reason ->
                    InlineBanner(message = reason, tone = BannerTone.Error)
                }
            }
            Spacer(Modifier.weight(1f))
            BottomSlot(visible = focused) {
                SecureHexKeyboard(
                    onKey = onKey,
                    onBackspace = onBackspace,
                    onDone = onDone,
                )
            }
            Spacer(Modifier.height(LenslySpacing.md))
        }
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
    LenslyScreen(
        title = "Check address",
        modifier = modifier,
        onBack = onBack,
        bottomAction = {
            PillButton(
                text = "Confirm address",
                onClick = onConfirm,
                accent = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WalletEyebrow("Import")
            Spacer(Modifier.width(LenslySpacing.sm))
            PillChip(text = "EVM")
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        DotHeadline(text = "ADDRESS")
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = "This key controls the address below. Check it is the one you expect before you continue.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AddressIcon(size = 16.dp)
                Spacer(Modifier.width(LenslySpacing.xs))
                WalletEyebrow("Account 1")
            }
            Text(
                text = groupedAddress(address),
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = SpaceMonoFamily),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
