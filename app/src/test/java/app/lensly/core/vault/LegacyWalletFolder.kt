package app.lensly.core.vault

import java.io.File

/** Writes wallet records the way an earlier build did: sealed under the wallet key at their own size, with no padding. */
internal object LegacyWalletFolder {
    /** Writes [bytes] as record [name] of wallet [walletId], sealed with no padding. [vaultKey] is the vault's key, not zeroed here. */
    fun writeRecord(layout: VaultLayout, vaultKey: ByteArray, walletId: String, name: String, bytes: ByteArray) {
        val pwk = File(layout.walletDir(walletId), WALLET_KEY_FILE_NAME).readBytes()
        val walletKey = Aead.open(vaultKey, walletId.toByteArray(Charsets.UTF_8), decodeBlob(pwk))
        try {
            val sealed = Aead.seal(walletKey, WalletFolder.legacyAad(walletId, name), bytes)
            File(layout.walletDir(walletId), "$name.bin").writeBytes(encodeBlob(sealed))
        } finally {
            walletKey.fill(0)
        }
    }
}
