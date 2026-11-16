package app.lensly.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.app.DISPLAY_NAME_MAX_LENGTH
import app.lensly.app.PROFILE_TEXT_MAX_LENGTH
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.KeyboardHost
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.components.TextAction
import app.lensly.ui.profile.ProfileFieldRow
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle

/** The fields of the Address Book form. */
enum class AddressBookField { ADDRESS, NAME, LABEL }

/** The longest address the form takes: a Bitcoin address is at most 62 characters, an EVM address 42. */
const val ADDRESS_BOOK_ADDRESS_MAX_LENGTH = 64

/**
 * The form of one Address Book entry: its address, its name and its label. A new entry opens with the address it was started from, such as
 * a Review Record's recipient. The keyboard starts hidden and opens on the field tapped, as the account profile does. [onDelete] is set
 * only for an entry that is saved, and [error] is the reason a save was refused, shown until the next save.
 */
@Composable
fun AddressBookFormScreen(
    title: String,
    address: String,
    name: String,
    label: String,
    focused: AddressBookField?,
    onFocus: (AddressBookField?) -> Unit,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    error: String? = null,
) {
    val unfocus = { onFocus(null) }
    KeyboardHost(focused = focused != null, onUnfocus = unfocus) {
        LenslyScreen(
            title = title,
            modifier = modifier,
            onBack = onBack,
            bottomAction = {
                if (focused != null) {
                    SecureKeyboard(onKey = onKey, onBackspace = onBackspace, onDone = unfocus)
                } else {
                    WalletPill(text = "Save", onClick = onSave, modifier = Modifier.fillMaxWidth(), style = WalletPillStyle.Primary)
                }
            },
        ) {
            if (error != null) {
                InlineBanner(message = error, tone = BannerTone.Error)
                Spacer(Modifier.height(LenslySpacing.md))
            }
            ProfileFieldRow(
                label = "Address",
                value = address,
                placeholder = "0x or bc1 address",
                focused = focused == AddressBookField.ADDRESS,
                maxLength = ADDRESS_BOOK_ADDRESS_MAX_LENGTH,
                onFocus = { onFocus(AddressBookField.ADDRESS) },
            )
            Spacer(Modifier.height(LenslySpacing.md))
            ProfileFieldRow(
                label = "Address Name",
                value = name,
                placeholder = "Name this address",
                focused = focused == AddressBookField.NAME,
                maxLength = DISPLAY_NAME_MAX_LENGTH,
                onFocus = { onFocus(AddressBookField.NAME) },
            )
            Spacer(Modifier.height(LenslySpacing.md))
            ProfileFieldRow(
                label = "Address Label",
                value = label,
                placeholder = "No label",
                focused = focused == AddressBookField.LABEL,
                maxLength = PROFILE_TEXT_MAX_LENGTH,
                onFocus = { onFocus(AddressBookField.LABEL) },
            )
            if (onDelete != null) {
                Spacer(Modifier.height(LenslySpacing.xl))
                TextAction(text = "Delete address", onClick = onDelete, destructive = true)
            }
        }
    }
}
