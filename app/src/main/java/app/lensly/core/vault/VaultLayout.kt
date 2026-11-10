package app.lensly.core.vault

import java.io.File

/**
 * On-disk layout under `noBackupFilesDir/vault/`:
 * `header.bin`, `vault.key`, `failures.bin`, `failures.bak`, `vault-records/` (records that belong to the vault as a
 * whole, such as settings), and `wallets/<id>/` holding `pwk.bin` and the records.
 */
internal class VaultLayout(val root: File) {
    val header = File(root, "header.bin")
    val vaultKey = File(root, "vault.key")
    val wallets = File(root, "wallets")
    val vaultRecords = File(root, "vault-records")

    /** A vault exists once both the header and the wrapped vault key have been written. */
    fun exists(): Boolean = header.isFile && vaultKey.isFile

    fun walletDir(walletId: String): File = File(wallets, walletId)
}

/**
 * Contents of `vault.key`: magic "LNVK" (4), version (1), then the software-wrapped blob.
 * The wrapped blob is `AES-GCM_Ksw(hardwareBlob)`, where `hardwareBlob` is `AES-GCM_hw(vaultKey)`.
 */
internal object VaultKeyFile {
    private val MAGIC = "LNVK".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val PREFIX_BYTES = 4 + 1

    fun encode(wrapped: ByteArray): ByteArray = MAGIC + byteArrayOf(VERSION) + wrapped

    fun decode(bytes: ByteArray): ByteArray {
        // Minimum: the prefix, the software IV, and a hardware blob (IV, 32-byte key, tag) plus the software tag.
        val minimum = PREFIX_BYTES + Aead.IV_BYTES + Aead.IV_BYTES + 32 + 16 + 16
        if (bytes.size < minimum || !bytes.copyOf(4).contentEquals(MAGIC) || bytes[4] != VERSION) {
            throw VaultException("The vault key file is damaged or not in a format this app reads.")
        }
        return bytes.copyOfRange(PREFIX_BYTES, bytes.size)
    }
}
