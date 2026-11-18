package app.lensly.core.vault

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * On-disk layout under `noBackupFilesDir/vault/`:
 * `header.bin`, `vault.key`, `failures.bin`, `failures.bak`, `wallet-pad.bin`, `vault-records/` (records that belong to one vault
 * as a whole, such as settings), `review-pool/` (the Review Records of every wallet, in equal-size files outside the wallet
 * folders, see [ReviewPool]), and `wallets/<id>/` holding `pwk.bin` and the other records.
 *
 * The real vault and the decoy vault share these folders. Every file is sealed under the key of the vault that owns it,
 * so a session lists and opens only its own files (see [ownsWallet] and [VaultRecordNames]).
 *
 * The photos and videos are plain files under [photos] (`filesDir/photos` in the app), in `real/` and `decoy/` (see [photoDir]).
 * [wipeVault] removes the whole folder, both vaults' photos with it.
 */
class VaultLayout(val root: File, val photos: File = File(root, "photos")) {
    val header = File(root, "header.bin")
    val vaultKey = File(root, "vault.key")
    val wallets = File(root, "wallets")
    val vaultRecords = File(root, "vault-records")
    val reviewPool = File(root, "review-pool")

    /** A vault exists once both the header and the wrapped vault key have been written. */
    fun exists(): Boolean = header.isFile && vaultKey.isFile

    /**
     * True when files of a vault are on disk without a whole vault: the header or the key file is missing, or a wallet folder is left.
     * Creating a vault refuses them, because a new hardware key would replace the one they were sealed under.
     */
    fun isPartial(): Boolean = !exists() && (header.exists() || vaultKey.exists() || wallets.exists())

    fun walletDir(walletId: String): File = File(wallets, walletId)

    /** The media folder of the real vault, or of the Decoy Vault when [decoy] is true. The two are kept apart. */
    fun photoDir(decoy: Boolean): File = File(photos, if (decoy) "decoy" else "real")

    companion object {
        /** The app's layout: the vault under its no-backup files folder, and its photos under its files folder. */
        fun of(context: Context): VaultLayout {
            val app = context.applicationContext
            return VaultLayout(File(app.noBackupFilesDir, "vault"), File(app.filesDir, "photos"))
        }
    }
}

/** Which vault a slot holds. The role is stored inside the sealed slot, so the file does not show it. */
internal object VaultRole {
    const val REAL: Byte = 1
    const val DECOY: Byte = 2
}

/**
 * The vault format version. `header.bin` and `vault.key` both hold it after their magic, and the slot key label names it (see [Vault]).
 * It is 3 since a slot's software key became bound to the device's slot key. A file of any other version is refused as a format change,
 * and nothing is migrated from it (see [VaultFormatChangedException]).
 */
internal const val VAULT_FORMAT_VERSION: Byte = 3

/**
 * Contents of `vault.key`, which holds one slot for the real vault and one for the decoy vault that a Duress Password
 * opens. The file is magic "LNVK" (4), version [VAULT_FORMAT_VERSION] (1), then two slots of [SLOT_BYTES] each. A slot is
 * `nonce (16) || tag (16) || sealed (89)`, where `sealed = AES-GCM_Ksw(AES-GCM_hw(vaultKey || role))`, `role` is one
 * byte (see [VaultRole]), and `tag` is the first 16 bytes of HMAC-SHA256(vaultKey, label || nonce). An open vault finds
 * its own slot by that tag, so it never needs to open the other slot to know which one is its own. While no Duress
 * Password is set, the decoy slot is random bytes of the same length. The slots are written in random order, so the
 * file shows neither which slot is the real one nor whether a decoy exists.
 */
internal object VaultKeyFile {
    private val MAGIC = "LNVK".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = VAULT_FORMAT_VERSION
    private const val PREFIX_BYTES = 4 + 1
    const val KEY_BYTES = 32
    private const val NONCE_BYTES = 16
    private const val TAG_BYTES = 16
    private const val GCM_TAG_BYTES = 16

    /** Sealed blob: software IV, hardware blob (IV, key and role, tag), software tag. */
    private const val SEALED_BYTES = Aead.IV_BYTES + (Aead.IV_BYTES + KEY_BYTES + 1 + GCM_TAG_BYTES) + GCM_TAG_BYTES

    /** One slot: nonce, tag, then the sealed blob. */
    const val SLOT_BYTES = NONCE_BYTES + TAG_BYTES + SEALED_BYTES

    /** Size of every `vault.key`. It does not depend on the password, the vault, or whether a decoy exists. */
    const val FILE_BYTES = PREFIX_BYTES + 2 * SLOT_BYTES

    private val TAG_LABEL = "lensly.vault.slot-tag".toByteArray(Charsets.US_ASCII)

    /** AAD of the two wrapping layers. It keeps the layers apart and ties each blob to this format. */
    val SOFTWARE_AAD = "lensly.vault.key.sw.v3".toByteArray(Charsets.US_ASCII)
    val HARDWARE_AAD = "lensly.vault.key.hw.v3".toByteArray(Charsets.US_ASCII)

    /** The two slots of a file, in file order. */
    class Contents internal constructor(val slots: List<ByteArray>) {
        /** The sealed software blob of slot [index]. */
        fun sealedOf(index: Int): ByteArray = sealedPartOf(slots[index])
    }

    /**
     * Parses `vault.key`. A file of another version is a format change, see [VaultFormatChangedException]. Any other size or magic is
     * damaged.
     */
    fun decode(bytes: ByteArray): Contents {
        val damaged = VaultException("The vault key file is damaged or not in a format this application reads.")
        if (bytes.size < PREFIX_BYTES || !bytes.copyOf(4).contentEquals(MAGIC)) throw damaged
        if (bytes[4] != VERSION) throw VaultFormatChangedException()
        if (bytes.size != FILE_BYTES) throw damaged
        return Contents(
            listOf(
                bytes.copyOfRange(PREFIX_BYTES, PREFIX_BYTES + SLOT_BYTES),
                bytes.copyOfRange(PREFIX_BYTES + SLOT_BYTES, FILE_BYTES),
            ),
        )
    }

    /** The file for two [slots], written in random order. Each slot must be [SLOT_BYTES] long. */
    fun encode(slots: List<ByteArray>, random: SecureRandom): ByteArray {
        require(slots.size == 2 && slots.all { it.size == SLOT_BYTES }) { "A vault key file holds two slots." }
        val (first, second) = if (random.nextBoolean()) slots[0] to slots[1] else slots[1] to slots[0]
        return MAGIC + byteArrayOf(VERSION) + first + second
    }

    /** A slot that holds [sealed], the sealed blob of [vaultKey], under a fresh nonce and the tag that names the vault. */
    fun slotOf(vaultKey: ByteArray, sealed: ByteArray, random: SecureRandom): ByteArray {
        require(sealed.size == SEALED_BYTES) { "A sealed slot is $SEALED_BYTES bytes." }
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        return nonce + tagOf(vaultKey, nonce) + sealed
    }

    /** Random bytes of slot size. This is the decoy slot while no Duress Password is set. */
    fun randomSlot(random: SecureRandom): ByteArray = ByteArray(SLOT_BYTES).also { random.nextBytes(it) }

    /** The sealed blob inside [slot], after its nonce and tag. */
    fun sealedPartOf(slot: ByteArray): ByteArray = slot.copyOfRange(NONCE_BYTES + TAG_BYTES, slot.size)

    /** The index of the slot that [vaultKey] names, or -1 if none does. */
    fun ownerIndex(contents: Contents, vaultKey: ByteArray): Int =
        contents.slots.indexOfFirst { slot ->
            val nonce = slot.copyOfRange(0, NONCE_BYTES)
            val tag = slot.copyOfRange(NONCE_BYTES, NONCE_BYTES + TAG_BYTES)
            MessageDigest.isEqual(tag, tagOf(vaultKey, nonce))
        }

    private fun tagOf(vaultKey: ByteArray, nonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(vaultKey, "HmacSHA256")) }
        return mac.doFinal(TAG_LABEL + nonce).copyOf(TAG_BYTES)
    }
}
