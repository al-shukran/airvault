package app.lensly.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.app.ADDRESS_NOTES_MAX_LENGTH
import app.lensly.app.DISPLAY_NAME_MAX_LENGTH
import app.lensly.app.PROFILE_TEXT_MAX_LENGTH
import app.lensly.app.bitcoinNetworkName
import app.lensly.app.btcNetworkOf
import app.lensly.app.chainOfAddress
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.KeyboardHost
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.components.SettingsIsland
import app.lensly.ui.components.SettingsRow
import app.lensly.ui.profile.ProfileFieldRow
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalMonoFamily
import app.lensly.ui.wallet.WalletEyebrow
import app.lensly.ui.wallet.WalletPill
import app.lensly.ui.wallet.WalletPillStyle

/** The fields of the Address Book form. */
enum class AddressBookField { ADDRESS, NAME, GROUP, LABEL, NOTES }

/** The longest address the form takes: a Bitcoin address is at most 62 characters, an EVM address 42. */
const val ADDRESS_BOOK_ADDRESS_MAX_LENGTH = 64

/** The network [address] is on, as the form names it, or null when it is not a valid address of Ethereum, Bitcoin, Solana or Algorand. */
internal fun addressNetworkName(address: String): String? = when (chainOfAddress(address)?.namespace) {
    "eip155" -> "Ethereum and EVM Networks"
    "bip122" -> btcNetworkOf(address)?.let(::bitcoinNetworkName)
    "solana" -> "Solana"
    "algorand" -> "Algorand"
    else -> null
}

/**
 * The form of one Address Book entry: its address, its network, its name, its group, its label and its notes. A new entry from a record opens
 * with the address it was started from, which is fixed, since it is the key of the entry; so does a saved entry. Only a new entry the owner
 * starts from the list takes a typed address, and its network follows the address. The keyboard starts hidden and opens on the field tapped,
 * as the account profile does. [onDelete] is set only for an entry that is saved, and [error] is the reason a save was refused, shown until
 * the next save.
 */
@Composable
fun AddressBookFormScreen(
    title: String,
    address: String,
    name: String,
    group: String,
    label: String,
    notes: String,
    focused: AddressBookField?,
    onFocus: (AddressBookField?) -> Unit,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** True when the address is fixed: it came from a record, or the entry is saved, so it is shown and not typed. */
    addressFixed: Boolean = false,
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
            SettingsIsland("Entry") {
                FormCell {
                    if (addressFixed) {
                        ReadOnlyRow(label = "Address", value = address, mono = true)
                    } else {
                        ProfileFieldRow(
                            label = "Address",
                            value = address,
                            placeholder = "Ethereum, Bitcoin, Solana or Algorand address",
                            focused = focused == AddressBookField.ADDRESS,
                            maxLength = ADDRESS_BOOK_ADDRESS_MAX_LENGTH,
                            onFocus = { onFocus(AddressBookField.ADDRESS) },
                        )
                    }
                }
                FormCell {
                    ReadOnlyRow(label = "Network", value = addressNetworkName(address) ?: "Set by the address", mono = false)
                }
                FormCell {
                    ProfileFieldRow(
                        label = "Address Name",
                        value = name,
                        placeholder = "Name this address",
                        focused = focused == AddressBookField.NAME,
                        maxLength = DISPLAY_NAME_MAX_LENGTH,
                        onFocus = { onFocus(AddressBookField.NAME) },
                    )
                }
                FormCell {
                    ProfileFieldRow(
                        label = "Address Group",
                        value = group,
                        placeholder = "No group",
                        focused = focused == AddressBookField.GROUP,
                        maxLength = PROFILE_TEXT_MAX_LENGTH,
                        onFocus = { onFocus(AddressBookField.GROUP) },
                    )
                }
                FormCell {
                    ProfileFieldRow(
                        label = "Address Label",
                        value = label,
                        placeholder = "No label",
                        focused = focused == AddressBookField.LABEL,
                        maxLength = PROFILE_TEXT_MAX_LENGTH,
                        onFocus = { onFocus(AddressBookField.LABEL) },
                    )
                }
                FormCell {
                    ProfileFieldRow(
                        label = "Address Notes",
                        value = notes,
                        placeholder = "No notes",
                        focused = focused == AddressBookField.NOTES,
                        maxLength = ADDRESS_NOTES_MAX_LENGTH,
                        onFocus = { onFocus(AddressBookField.NOTES) },
                    )
                }
            }
            if (onDelete != null) {
                SettingsIsland("Delete") {
                    SettingsRow(title = "Delete Address", onClick = onDelete, destructive = true, divided = false)
                }
            }
        }
    }
}

/**
 * One field of the form inside its island. A field draws its own outline, so it gets a vertical band of its own, and the island has no
 * divider between two fields.
 */
@Composable
private fun FormCell(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = LenslySpacing.md)) {
        content()
    }
}

/** A value the form shows and does not take: its label above it, in the same place a field has. */
@Composable
private fun ReadOnlyRow(label: String, value: String, mono: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        WalletEyebrow(label)
        Spacer(Modifier.height(LenslySpacing.xs))
        Text(
            text = value,
            style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMonoFamily.current) else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
