package app.lensly.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.DISPLAY_NAME_MAX_LENGTH
import app.lensly.ui.components.BottomSlot
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.KeyboardHost
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.theme.LenslyOutlineInteractive
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceContainer
import app.lensly.ui.theme.LenslyWarning

/** Name suggested before the owner types one. */
const val DEFAULT_WALLET_NAME = "WALLET 1"

/** Name used when the owner confirms without typing one and no suggestion has loaded yet. */
private const val FALLBACK_WALLET_NAME = "WALLET"

private val NAME_FIELD_HEIGHT = 56.dp

/**
 * The name a wallet is saved under. [typed] is only what the owner typed, so the suggestion never mixes
 * into it. Blank input (empty or whitespace only) falls back to [suggested].
 */
fun effectiveWalletName(typed: String, suggested: String?): String =
    typed.trim().ifEmpty { suggested ?: FALLBACK_WALLET_NAME }

/**
 * The name screen. It names a new wallet, and it renames a wallet, an account, or several accounts. The caller owns [name]
 * and enforces [maxLength], which is [DISPLAY_NAME_MAX_LENGTH] unless a batch name is shorter. [placeholder] is shown muted
 * while [name] is empty and is not part of the name. When [onReset] is set, a pill labelled [resetLabel] is shown under the
 * field. [title] and [emptyHelp] replace the default title and the empty-input helper when they are set. Taps go through
 * [SecureKeyboard], and DONE finishes the flow. The keyboard is up when the screen opens. A tap outside the field or the system
 * Back hides it, and a tap on the field brings it back.
 *
 * Under the field, a counter shows the length. Empty input says what DONE will use, and a full field says it is full.
 */
@Composable
fun WalletNameScreen(
    name: String,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    onReset: (() -> Unit)? = null,
    resetLabel: String = "Reset",
    title: String? = null,
    maxLength: Int = DISPLAY_NAME_MAX_LENGTH,
    emptyHelp: String? = null,
) {
    val full = name.length >= maxLength
    val helperColor = if (full) LenslyWarning else MaterialTheme.colorScheme.onSurfaceVariant
    var focused by rememberSaveable { mutableStateOf(true) }
    KeyboardHost(focused = focused, onUnfocus = { focused = false }) {
        LenslyScreen(
            title = title ?: if (onReset != null) "Account name" else "Wallet name",
            modifier = modifier,
            scrollable = false,
            bottomAction = {
                BottomSlot(visible = focused) {
                    SecureKeyboard(
                        onKey = onKey,
                        onBackspace = onBackspace,
                        onDone = onDone,
                    )
                }
            },
        ) {
            WalletEyebrow("Name")
            Spacer(Modifier.height(LenslySpacing.xs))
            DotHeadline(text = "NAME IT")
            Spacer(Modifier.height(LenslySpacing.sm))
            Text(
                text = "You can change it later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(LenslySpacing.lg))
            NameField(name = name, placeholder = placeholder, full = full, focused = focused, onFocus = { focused = true })
            Spacer(Modifier.height(LenslySpacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        full -> "Name is full"
                        name.isEmpty() -> emptyHelp ?: "Leave empty to use ${placeholder ?: "the suggested name"}"
                        else -> "Up to $maxLength characters"
                    }.uppercase(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = helperColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(LenslySpacing.xs))
                Text(
                    text = "${name.length}/$maxLength",
                    style = MaterialTheme.typography.labelMedium,
                    color = helperColor,
                )
            }
            if (onReset != null) {
                Spacer(Modifier.height(LenslySpacing.lg))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    WalletPill(
                        text = resetLabel,
                        onClick = onReset,
                        style = WalletPillStyle.Outline,
                        size = WalletPillSize.Compact,
                    )
                }
            }
        }
    }
}

/**
 * The name as typed, with a caret, or the placeholder in muted text while the field is empty. The caret shows only while the field
 * has focus, and not once the name is full, since nothing more can be typed. A long name wraps onto a second line rather than cutting
 * off. A tap calls [onFocus].
 */
@Composable
private fun NameField(name: String, placeholder: String?, full: Boolean, focused: Boolean, onFocus: () -> Unit) {
    val shape = RoundedCornerShape(LenslyRadius.lg)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NAME_FIELD_HEIGHT)
            .clip(shape)
            .background(LenslySurfaceContainer, shape)
            .border(1.dp, if (full) LenslyWarning else LenslyOutlineInteractive, shape)
            .clickable(role = Role.Button, onClick = onFocus)
            .padding(horizontal = LenslySpacing.sm, vertical = LenslySpacing.sm),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (name.isEmpty()) {
            Text(
                text = placeholder ?: "No name",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = if (focused && !full) "${name}_" else name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
