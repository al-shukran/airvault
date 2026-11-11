package app.lensly.app.export

/**
 * The per-account export: one account's address, with its derivation path as text. It is public data.
 *
 * It is not a ur:crypto-hdkey. Rabby and MetaMask pair by the account key m/44'/60'/0' ([PairingExport]), and the wire
 * spec has no encoding that makes them import one non-hardened child key as one account. So the code carries only the
 * address, a plain string with no scheme prefix and no UR wrapper.
 */
object AccountExport {

    /** The QR payload for an account: its EIP-55 address exactly as it is shown. */
    fun qrParts(address: String): List<String> = listOf(address)

    /** The derivation path of account [index] of a Mnemonic wallet, m/44'/60'/0'/0/[index]. */
    fun derivationPath(index: Int): String {
        require(index >= 0) { "index must be a non-hardened child number (0 until 2^31)" }
        return "m/44'/60'/0'/0/$index"
    }
}
