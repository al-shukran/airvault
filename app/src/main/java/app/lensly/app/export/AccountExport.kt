package app.lensly.app.export

import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.core.ur.CryptoHdKeyEncoder
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.toHex

/**
 * What one account's export shows: its address and derivation path, the frames of its code, and the fields of the plain-text
 * view. Public data only.
 *
 * [parts] is the code. A Mnemonic account's code is the ur:crypto-hdkey of its own address key, m/44'/60'/0'/0/[index], with the
 * full path as its origin, depth 5, and the fingerprint of m/44'/60'/0'/0 as its parent. It carries no private key, and an online
 * wallet can derive the address from it. A Single Key has no account key, so its code is its address alone and it has no path or
 * fields.
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
    private const val DEFAULT_NAME = "Lensly"
    private const val ADDRESS_DEPTH = 5

    /** The derivation path of account [index] of a Mnemonic wallet, m/44'/60'/0'/0/[index]. */
    fun derivationPath(index: Int): String {
        require(index >= 0) { "index must be a non-hardened child number (0 until 2^31)" }
        return "m/44'/60'/0'/0/$index"
    }

    /**
     * The export of account [index] of [wallet], whose EIP-55 address is [address]. The code's name is the wallet's name, or "Lensly"
     * when the wallet has none.
     */
    fun view(wallet: WalletMeta, index: Int, address: String): AccountExportView {
        val xpub = wallet.accountXpub
        val masterFingerprint = wallet.masterFingerprintHex?.toLong(16)?.toInt()
        if (wallet.kind != WalletKind.MNEMONIC || xpub == null || masterFingerprint == null) {
            return AccountExportView(address = address, derivationPath = null, parts = listOf(address), fields = emptyList())
        }
        val path = derivationPath(index)
        val key = AccountAddresses.addressXpub(xpub, index)
        return AccountExportView(
            address = address,
            derivationPath = path,
            parts = partsOf(
                CryptoHdKeyEncoder.urParts(
                    pubkey33 = key.publicKey,
                    chainCode32 = key.chainCode,
                    masterFp = masterFingerprint,
                    parentFp = key.parentFingerprint,
                    name = wallet.name.ifBlank { DEFAULT_NAME },
                    origin = CryptoHdKeyEncoder.ACCOUNT_ORIGIN + listOf(0, index),
                    depth = ADDRESS_DEPTH,
                ),
            ),
            fields = listOf(
                "Derivation path" to path,
                "Depth" to ADDRESS_DEPTH.toString(),
                "Public key" to key.publicKey.toHex(),
                "Chain code" to key.chainCode.toHex(),
                "Parent fingerprint" to "%08x".format(key.parentFingerprint),
                "Master fingerprint" to "%08x".format(masterFingerprint),
            ),
        )
    }
}
