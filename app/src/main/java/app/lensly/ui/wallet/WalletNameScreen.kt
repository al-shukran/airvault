package app.lensly.ui.wallet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.SecureKeyboard

/** Longest name the screen accepts. The caller enforces it when it appends a character. */
const val WALLET_NAME_MAX_LENGTH = 24

/** Name suggested before the owner types one. */
const val DEFAULT_WALLET_NAME = "WALLET 1"

/** Name used when the owner confirms without typing one and no suggestion has loaded yet. */
private const val FALLBACK_WALLET_NAME = "WALLET"

/**
 * The name a wallet is saved under. [typed] is only what the owner typed, so the suggestion never mixes
 * into it. Blank input (empty or whitespace only) falls back to [suggested].
 */
fun effectiveWalletName(typed: String, suggested: String?): String =
    typed.trim().ifEmpty { suggested ?: FALLBACK_WALLET_NAME }

/**
 * Last step of the wallet flow: name the wallet. The caller owns [name] and enforces
 * [WALLET_NAME_MAX_LENGTH]. [placeholder] is shown muted while [name] is empty and is not part of the name.
 * Taps go through [SecureKeyboard], and DONE finishes the flow.
 */
@Composable
fun WalletNameScreen(
    name: String,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    WalletScaffold(
        modifier = modifier,
        scrollable = false,
        footer = {
            SecureKeyboard(
                onKey = onKey,
                onBackspace = onBackspace,
                onDone = onDone,
            )
        },
    ) {
        Spacer(Modifier.height(24.dp))
        WalletEyebrow("Name")
        Spacer(Modifier.height(12.dp))
        DotHeadline(text = "NAME IT")
        Spacer(Modifier.height(16.dp))
        PillChip(text = "Up to $WALLET_NAME_MAX_LENGTH characters")
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (name.isEmpty()) {
                Text(
                    text = placeholder ?: "No name",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "${name}_",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        WalletEyebrow("You can change it later")
        Spacer(Modifier.weight(1f))
    }
}
