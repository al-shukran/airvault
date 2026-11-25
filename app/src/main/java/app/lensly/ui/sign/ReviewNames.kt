package app.lensly.ui.sign

import androidx.compose.ui.graphics.Color
import app.lensly.app.AddressNotes
import app.lensly.app.SigningAccount
import app.lensly.app.addressKeyOf
import app.lensly.app.notesOf
import app.lensly.app.plainName
import app.lensly.app.plainNameOrNull
import app.lensly.ui.theme.LumenPalette

/**
 * Whose name a counterparty's name is, which sets its colour on a review and in an activity. [ACCOUNT] is an account of this device,
 * and it is green. [BOOK] is an Address Book entry that is not an account of this device, and it is blue.
 */
internal enum class NameRole { ACCOUNT, BOOK }

/**
 * The role of an address's name: [NameRole.ACCOUNT] when this device has the address, also when the Address Book holds it, [NameRole.BOOK]
 * when only the Address Book does, and null when neither names it.
 */
internal fun nameRoleOf(notes: AddressNotes?): NameRole? = when {
    notes?.internal != null -> NameRole.ACCOUNT
    notes?.entry != null -> NameRole.BOOK
    else -> null
}

/** The colour of a name with this role on [palette]: the success role for an account of this device, the info role for an entry. */
internal fun NameRole.colourIn(palette: LumenPalette): Color = when (this) {
    NameRole.ACCOUNT -> palette.positive
    NameRole.BOOK -> palette.info
}

/** A name as a sentence shows it, with the role that colours it. */
internal data class PartyName(val text: String, val role: NameRole)

/**
 * The name of a party to a send: "Account (Book)" when this device and the Address Book both name the address, else the one that does. Null
 * when neither does, so the address is shown in full.
 */
internal fun partyNameOf(account: String?, book: String?): PartyName? = when {
    account != null -> PartyName(if (book != null) "$account ($book)" else account, NameRole.ACCOUNT)
    book != null -> PartyName(book, NameRole.BOOK)
    else -> null
}

/**
 * The name of [address] as a send names it. The signing account is named as the review names it, by its name in [signer]. Any other address
 * is named by its account of this device and its Address Book entry, which [notes] hold.
 */
internal fun sendPartyOf(address: String, notes: Map<String, AddressNotes>, signer: SigningAccount): PartyName? {
    val known = notes.notesOf(address)
    val account = if (addressKeyOf(address) == addressKeyOf(signer.address)) {
        signer.name
    } else {
        known?.internal?.plainName()
    }
    return partyNameOf(plainNameOrNull(account), plainNameOrNull(known?.entry?.name))
}
