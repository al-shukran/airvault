package app.lensly.app

/**
 * The account that signs a request, as its review names it: the account's name (its Display Name, or its Auto-Name when it has none), the
 * name of its wallet, and its address. [isAddressKey] is true for a BIP44 address key, which belongs to no account, so its review says Key.
 * Every field is public.
 */
data class SigningAccount(val name: String, val walletName: String, val address: String, val isAddressKey: Boolean = false)
