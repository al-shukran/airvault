package app.lensly.app.export

import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.core.wallet.toHex

/**
 * What one account's export shows: its address and derivation path, the frames of its code, and the fields of the plain-text
 * view. Public data only.
 *
 * [parts] is the code. A Mnemonic account's code is the ur:crypto-hdkey of its account key m/44'/60'/0' ([PairingExport]), so
 * every account of one wallet carries the same key, and an online wallet with that key derives each account's address. A Single
 * Key has no account key, so its code is its address alone and it has no path or fields.
 *
 * [fields] follow the address, in the order the text view lists them. Each value is in full.
 */
data class AccountExportView(
    val address: String,
    /** The full derivation path, m/44'/60'/0'/0/[index], or null for a Single Key. */
    val derivationPath: String?,
    val parts: List<String>,
    val fields: List<Pair<String, String>>,
)

/** The per-account export of a Mnemonic or Single Key wallet. */
object AccountExport {
    private const val ORIGIN_PATH = "m/44'/60'/0'"
    private const val DEFAULT_NAME = "Lensly"

    /** The derivation path of account [index] of a Mnemonic wallet, m/44'/60'/0'/0/[index]. */
    fun derivationPath(index: Int): String {
        require(index >= 0) { "index must be a non-hardened child number (0 until 2^31)" }
        return "m/44'/60'/0'/0/$index"
    }

    /**
     * The export of account [index] of [wallet], whose EIP-55 address is [address]. The code's name is the wallet's name, as the
     * pairing code uses it, or "Lensly" when the wallet has none.
     */
    fun view(wallet: WalletMeta, index: Int, address: String): AccountExportView {
        val xpub = wallet.accountXpub
        val masterFingerprint = wallet.masterFingerprintHex?.toLong(16)?.toInt()
        if (wallet.kind != WalletKind.MNEMONIC || xpub == null || masterFingerprint == null) {
            return AccountExportView(address = address, derivationPath = null, parts = listOf(address), fields = emptyList())
        }
        val path = derivationPath(index)
        return AccountExportView(
            address = address,
            derivationPath = path,
            parts = PairingExport.urParts(xpub, masterFingerprint, wallet.name.ifBlank { DEFAULT_NAME }),
            fields = listOf(
                "Derivation path" to path,
                "Origin path" to ORIGIN_PATH,
                "Account public key" to xpub.publicKey.toHex(),
                "Chain code" to xpub.chainCode.toHex(),
                "Parent fingerprint" to "%08x".format(xpub.parentFingerprint),
                "Master fingerprint" to "%08x".format(masterFingerprint),
            ),
        )
    }
}
