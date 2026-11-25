package app.lumio.ui.settings

import app.lumio.DebugDemoData
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.lensly.ui.theme.ClearAirSpacing
import app.lumio.pair.PairedWallet
import app.lumio.ui.kit.ClearAirDialog
import app.lumio.ui.kit.ClearAirNote
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.pair.PairLabels

/** The test tag of the row that pairs a wallet from Lensly. */
const val PAIR_WALLET_TAG = "pair-wallet"

/** The test tag of the confirm button of a deletion. */
const val DELETE_CONFIRM_TAG = "delete-confirm"

/** The test tag of a wallet's delete row, by the wallet's ID. */
fun deleteWalletTag(walletId: String): String = "delete-$walletId"

/** The test tag of a wallet's Address Book row, by the wallet's ID. */
fun addressBookTag(walletId: String): String = "address-book-$walletId"

private fun addressBookDescription(count: Int): String = when (count) {
    0 -> "No saved addresses"
    1 -> "1 saved address"
    else -> "$count saved addresses"
}

/**
 * The Wallets page: a row that pairs a wallet from Lensly, then each paired wallet with its fingerprint and a delete row in rose.
 * A deletion asks first, and it erases only Lumio's watch-only copy. The wallet in Lensly is not changed. A wallet's address book is on the
 * Address book page, not here.
 */
@Composable
fun WalletsSettings(wallets: List<PairedWallet>, onPair: () -> Unit, onDelete: (PairedWallet) -> Unit) {
    var pendingDelete by remember { mutableStateOf(wallets.firstOrNull().takeIf { DebugDemoData.flag("wallet:remove") }) }
    ClearAirRow(
        title = "Pair from Lensly",
        description = "Add a wallet's watch-only copy. Lensly keeps the keys.",
        onClick = onPair,
        modifier = Modifier.testTag(PAIR_WALLET_TAG),
    )
    if (wallets.isEmpty()) {
        ClearAirNote(text = "No wallets paired yet.", modifier = Modifier.padding(top = ClearAirSpacing.xs))
    }
    wallets.forEach { wallet ->
        ClearAirRow(
            title = wallet.name,
            description = "Fingerprint ${PairLabels.fingerprint(wallet.fingerprint)}",
        )
        // Pairing again replaces this wallet's watch-only copy and its address book, so the wallet keeps its place in the list.
        ClearAirRow(title = "Re-Pair with Lensly", onClick = onPair)
        ClearAirRow(
            title = "Remove from Lumio",
            onClick = { pendingDelete = wallet },
            destructive = true,
            modifier = Modifier.testTag(deleteWalletTag(wallet.id)),
        )
    }
    pendingDelete?.let { wallet ->
        ClearAirDialog(
            title = "Remove ${wallet.name} from Lumio?",
            text = "Lensly and your keys are not affected. Names come from Lensly and can't be edited here.",
            onDismiss = { pendingDelete = null },
        ) {
            ClearAirPrimaryButton(
                text = "Remove",
                onClick = {
                    pendingDelete = null
                    onDelete(wallet)
                },
                destructive = true,
                modifier = Modifier.testTag(DELETE_CONFIRM_TAG),
            )
            ClearAirTextAction(text = "Cancel", onClick = { pendingDelete = null })
        }
    }
}

/**
 * The Address book rows: each paired wallet, with how many addresses its book holds. A tap on a wallet opens its book.
 */
@Composable
fun AddressBookRows(wallets: List<PairedWallet>, onAddressBook: (PairedWallet) -> Unit) {
    if (wallets.isEmpty()) {
        ClearAirNote(text = "No wallets paired yet.")
    }
    wallets.forEach { wallet ->
        ClearAirRow(
            title = wallet.name,
            description = addressBookDescription(wallet.addressBook.size),
            onClick = { onAddressBook(wallet) },
            modifier = Modifier.testTag(addressBookTag(wallet.id)),
        )
    }
}
