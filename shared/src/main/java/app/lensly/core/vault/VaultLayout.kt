package app.lensly.core.vault

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * On-disk layout under `noBackupFilesDir/data/`:
 * `header.bin`, `unlock.bin`, `failures.bin`, `failures.bak`, `spare.bin`, `wipe.bin` (only while an erase is under way), `records/`
 * (records that belong to one vault as a whole, such as settings), `review-pool/` (the Review Records of every wallet, in equal-size files outside the wallet
 * folders, see [ReviewPool]), and `items/<id>/` holding the key file and the other records.
 *
 * The real vault and the decoy vault share these folders. Every file is sealed under the key of the vault that owns it,
 * so a session lists and opens only its own files (see [ownsWallet] and [VaultRecordNames]).
 *
 * The photos and videos are sealed files (see [MediaEnvelope]) under [photos] (`filesDir/photos` in the app), in the folders `0` and `1`
 * (see [photoDir]). Both exist from setup, and no file name says what a file is: its kind and capture time are sealed inside it.
 * [wipeVault] removes the whole folder, both vaults' photos with it.
 */
class VaultLayout(val root: File, val photos: File = File(root, "photos")) {
    val header = File(root, "header.bin")
    val vaultKey = File(root, "unlock.bin")
    val wallets = File(root, "items")
    val vaultRecords = File(root, "records")
    val reviewPool = File(root, "review-pool")

    /**
     * Written before the first key goes, and removed after the last file (see [wipeVault]). While it is there, the vault is no vault: an erase
     * that stopped half way is finished by [Vault.finishPendingWipe].
     */
    val wipeMarker = File(root, "wipe.bin")

    /** A vault exists once both the header and the wrapped vault key have been written, and no erase is under way (see [wipeMarker]). */
    fun exists(): Boolean = header.isFile && vaultKey.isFile && !wipeMarker.exists()

    /**
     * True when files of a vault are on disk without a whole vault: the header or the key file is missing, a wallet folder is left, or an
     * erase is under way. Creating a vault refuses them, because a new hardware key would replace the one they were sealed under.
     */
    fun isPartial(): Boolean = !exists() && (header.exists() || vaultKey.exists() || wallets.exists() || wipeMarker.exists())

    /**
     * True before the first setup: no vault, and no vault files left. Photos taken then are sealed to the pending key
     * (see [PendingPhotoKey]).
     */
    fun isBeforeSetup(): Boolean = !exists() && !isPartial()

    fun walletDir(walletId: String): File = File(wallets, walletId)

    /** The key file of wallet [walletId]: its Per-Wallet Key, wrapped under the vault key that owns the wallet. */
    fun walletKeyFile(walletId: String): File = File(walletDir(walletId), WalletFolder.KEY_FILE_NAME)

    /** The file of record [name] of wallet [walletId]. See [WalletFolder.fileNameOf]. */
    fun walletRecordFile(walletId: String, name: String): File = File(walletDir(walletId), WalletFolder.fileNameOf(name))

    /** The media folder of the real vault (`0`), or of the Decoy Vault (`1`). The two are kept apart. */
    fun photoDir(decoy: Boolean): File = File(photos, if (decoy) "1" else "0")

    /**
     * Makes both media folders. They exist from setup, so the folder listing does not show whether a Decoy Vault was made.
     */
    fun makePhotoFolders() {
        photoDir(decoy = false).mkdirs()
        photoDir(decoy = true).mkdirs()
    }

    /**
     * The public key that the photos of the real vault, or of the Decoy Vault, are sealed to. It is a plain file beside the photo folders,
     * so the cover can seal without a session. Until the Decoy Vault has a key of its own, its file holds [placeholderPhotoKey], so the
     * folder shows the same files whether or not a decoy exists.
     */
    fun photoKeyFile(decoy: Boolean): File = File(photos, if (decoy) "1.bin" else "0.bin")

    /**
     * The private half of the photo key pair that photos taken before the first setup are sealed to, wrapped under a Keystore key (see
     * [PendingPhotoKey]). It sits in the photo folder, so a wipe removes it with the photos.
     */
    val pendingKeyFile = File(photos, "pending.bin")

    companion object {
        /** The vault's folder under the app's no-backup files folder. Its name says nothing about what the folder holds. */
        const val DIR_NAME = "data"

        /** The app's layout: the vault under its no-backup files folder, and its photos under its files folder. */
        fun of(context: Context): VaultLayout {
            val app = context.applicationContext
            return VaultLayout(File(app.noBackupFilesDir, DIR_NAME), File(app.filesDir, "photos"))
        }
    }
}

/** Random bytes of public key size, in the place of a key file that no key of its own has filled yet. */
internal fun placeholderPhotoKey(): ByteArray = ByteArray(MediaEnvelope.KEY_BYTES).also { SecureRandom().nextBytes(it) }

/** Which vault a slot holds. The role is stored inside the sealed slot, so the file does not show it. */
internal object VaultRole {
    const val REAL: Byte = 1
    const val DECOY: Byte = 2
}

/**
 * The vault format version. `header.bin` and `unlock.bin` both hold it after their magic, and the slot key label names it (see [Vault]).
 * A vault with a file of any other version is unverifiable, so an unlock erases it, and nothing is migrated (see [VaultFormatChangedException]).
 */
internal const val VAULT_FORMAT_VERSION: Byte = 4

/**
 * Contents of `unlock.bin`, which holds one slot for the real vault and one for the decoy vault that a Duress Password
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

    /** Size of every `unlock.bin`. It does not depend on the password, the vault, or whether a decoy exists. */
    const val FILE_BYTES = PREFIX_BYTES + 2 * SLOT_BYTES

    private val TAG_LABEL = "lensly.vault.slot-tag".toByteArray(Charsets.US_ASCII)

    /** AAD of the two wrapping layers. It keeps the layers apart and ties each blob to this format. */
    val SOFTWARE_AAD = "lensly.vault.key.sw.v$VAULT_FORMAT_VERSION".toByteArray(Charsets.US_ASCII)
    val HARDWARE_AAD = "lensly.vault.key.hw.v$VAULT_FORMAT_VERSION".toByteArray(Charsets.US_ASCII)

    /** SHA-256 of the file [bytes] in lower-case hex. The Failure Delay state holds it, so only the file last written opens (see [FailureState.keyFileDigest]). */
    fun digestOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** The two slots of a file, in file order. */
    class Contents internal constructor(val slots: List<ByteArray>) {
        /** The sealed software blob of slot [index]. */
        fun sealedOf(index: Int): ByteArray = sealedPartOf(slots[index])
    }

    /**
     * Parses `unlock.bin`. A file of another version throws [VaultFormatChangedException], and an unlock erases the vault. Any other size or
     * magic is damaged.
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
