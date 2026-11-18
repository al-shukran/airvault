package app.lensly.app

/**
 * The internal account of this device that an address is: its Display Name when it has one, its Auto-Name, and its label when it has
 * one. The Auto-Name is always there, so an account without a Display Name still has a name to show. [walletName] names the wallet the
 * account belongs to when that is another wallet of the vault. It is null for an account of the signing wallet.
 */
data class InternalAccount(val displayName: String?, val autoName: String, val label: String?, val walletName: String? = null)

/**
 * What the device's accounts and the Address Book say of one address. Either part can be absent, and both can be present. A review shows
 * both, so an address that is an account of this device and also an entry shows its internal names and the entry's names.
 */
data class AddressNotes(val internal: InternalAccount?, val entry: AddressBookEntry?)

/**
 * The notes of [address] on [chain], or null when neither the device's [internal] accounts nor the Address Book [book] know it. The entry
 * is found by [addressBookEntryFor], so it must be on [chain].
 */
fun addressNotesOf(
    address: String,
    chain: AddressChain,
    internal: Map<String, InternalAccount>,
    book: List<AddressBookEntry>,
): AddressNotes? {
    val account = internal[addressKeyOf(address)]
    val entry = addressBookEntryFor(address, chain, book)
    return if (account == null && entry == null) null else AddressNotes(account, entry)
}

/** The notes of each of [addresses] on [chain] that either source knows, keyed by [addressKeyOf]. */
fun addressNotesOf(
    addresses: Collection<String>,
    chain: AddressChain,
    internal: Map<String, InternalAccount>,
    book: List<AddressBookEntry>,
): Map<String, AddressNotes> = addresses.distinct()
    .mapNotNull { address -> addressNotesOf(address, chain, internal, book)?.let { addressKeyOf(address) to it } }
    .toMap()

/** The notes of [address] in a map that [addressNotesOf] built, or null when there are none. */
fun Map<String, AddressNotes>.notesOf(address: String): AddressNotes? = this[addressKeyOf(address)]

/** The start of the bold line of an account of this device. */
const val YOUR_ACCOUNT = "Your account"

/**
 * The bold line above an address. An account of this device takes it, as "Your account · <name> · <label>", and the label is left out when the
 * account has none. Otherwise it is the Address Book name. Null when neither gives one.
 */
fun AddressNotes.headline(): String? {
    val account = internal ?: return entry?.name
    return listOfNotNull(YOUR_ACCOUNT, account.displayName ?: account.autoName, account.label).joinToString(" · ")
}
