package app.lensly.core.vault

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
 */
class VaultLayout(val root: File) {
    val header = File(root, "header.bin")
    val vaultKey = File(root, "vault.key")
    val wallets = File(root, "wallets")
    val vaultRecords = File(root, "vault-records")
    val reviewPool = File(root, "review-pool")

    /** A vault exists once both the header and the wrapped vault key have been written. */
    fun exists(): Boolean = header.isFile && vaultKey.isFile

    fun walletDir(walletId: String): File = File(wallets, walletId)
}

/** Which vault a slot holds. The role is stored inside the sealed slot, so the file does not show it. */
internal object VaultRole {
    const val REAL: Byte = 1
    const val DECOY: Byte = 2
}

/**
 * Contents of `vault.key`, which holds one slot for the real vault and one for the decoy vault that a Duress Password
 * opens. Two layouts exist.
 *
 * Version 2, the current one: magic "LNVK" (4), version 2 (1), then two slots of [SLOT_BYTES] each. A slot is
 * `nonce (16) || tag (16) || sealed (89)`, where `sealed = AES-GCM_Ksw(AES-GCM_hw(vaultKey || role))`, `role` is one
 * byte (see [VaultRole]), and `tag` is the first 16 bytes of HMAC-SHA256(vaultKey, label || nonce). An open vault finds
 * its own slot by that tag, so it never needs to open the other slot to know which one is its own. While no Duress
 * Password is set, the decoy slot is random bytes of the same length. The slots are written in random order, so the
 * file shows neither which slot is the real one nor whether a decoy exists.
 *
 * Version 1, the legacy layout: magic, version 1, then one `sealed` blob of `AES-GCM_Ksw(AES-GCM_hw(vaultKey))`. It is
 * read only to migrate a vault created before Duress Passwords existed.
 */
internal object VaultKeyFile {
    private val MAGIC = "LNVK".toByteArray(Charsets.US_ASCII)
    private const val VERSION_LEGACY: Byte = 1
    private const val VERSION: Byte = 2
    private const val PREFIX_BYTES = 4 + 1
    const val KEY_BYTES = 32
    private const val NONCE_BYTES = 16
    private const val TAG_BYTES = 16
    private const val GCM_TAG_BYTES = 16

    /** Sealed blob of version 2: software IV, hardware blob (IV, key and role, tag), software tag. */
    private const val SEALED_BYTES = Aead.IV_BYTES + (Aead.IV_BYTES + KEY_BYTES + 1 + GCM_TAG_BYTES) + GCM_TAG_BYTES

    /** One slot of version 2: nonce, tag, then the sealed blob. */
    const val SLOT_BYTES = NONCE_BYTES + TAG_BYTES + SEALED_BYTES

    /** Size of every version 2 file. It does not depend on the password, the vault, or whether a decoy exists. */
    const val FILE_BYTES = PREFIX_BYTES + 2 * SLOT_BYTES

    /** Sealed blob of the legacy layout: software IV, hardware blob (IV, key, tag), software tag. */
    private const val LEGACY_SEALED_BYTES = Aead.IV_BYTES + (Aead.IV_BYTES + KEY_BYTES + GCM_TAG_BYTES) + GCM_TAG_BYTES
    private const val LEGACY_FILE_BYTES = PREFIX_BYTES + LEGACY_SEALED_BYTES

    private val TAG_LABEL = "lensly.vault.slot-tag".toByteArray(Charsets.US_ASCII)

    /** AAD of the two wrapping layers, by layout. It keeps the layers apart and ties each blob to its layout. */
    val SOFTWARE_AAD = "lensly.vault.key.sw.v2".toByteArray(Charsets.US_ASCII)
    val HARDWARE_AAD = "lensly.vault.key.hw.v2".toByteArray(Charsets.US_ASCII)
    val LEGACY_SOFTWARE_AAD = "lensly.vault.key.sw.v1".toByteArray(Charsets.US_ASCII)
    val LEGACY_HARDWARE_AAD = "lensly.vault.key.hw.v1".toByteArray(Charsets.US_ASCII)

    /** A decoded file. [slots] are whole slots in file order, or, for the legacy layout, its single sealed blob. */
    class Contents internal constructor(val legacy: Boolean, val slots: List<ByteArray>) {
        /** The sealed software blob of slot [index]. */
        fun sealedOf(index: Int): ByteArray = if (legacy) slots[index] else sealedPartOf(slots[index])
    }

    /** Parses `vault.key`. Any other size or magic is damaged, or a layout this app does not read. */
    fun decode(bytes: ByteArray): Contents {
        val damaged = VaultException("The vault key file is damaged or not in a format this app reads.")
        if (bytes.size < PREFIX_BYTES || !bytes.copyOf(4).contentEquals(MAGIC)) throw damaged
        return when {
            bytes[4] == VERSION && bytes.size == FILE_BYTES -> Contents(
                legacy = false,
                slots = listOf(
                    bytes.copyOfRange(PREFIX_BYTES, PREFIX_BYTES + SLOT_BYTES),
                    bytes.copyOfRange(PREFIX_BYTES + SLOT_BYTES, FILE_BYTES),
                ),
            )
            bytes[4] == VERSION_LEGACY && bytes.size == LEGACY_FILE_BYTES -> Contents(
                legacy = true,
                slots = listOf(bytes.copyOfRange(PREFIX_BYTES, LEGACY_FILE_BYTES)),
            )
            else -> throw damaged
        }
    }

    /** The version 2 file for two [slots], written in random order. Each slot must be [SLOT_BYTES] long. */
    fun encode(slots: List<ByteArray>, random: SecureRandom): ByteArray {
        require(slots.size == 2 && slots.all { it.size == SLOT_BYTES }) { "A vault key file holds two slots." }
        val (first, second) = if (random.nextBoolean()) slots[0] to slots[1] else slots[1] to slots[0]
        return MAGIC + byteArrayOf(VERSION) + first + second
    }

    /** The legacy file for [sealed]. Only tests write one; the app reads it and migrates it. */
    internal fun encodeLegacy(sealed: ByteArray): ByteArray = MAGIC + byteArrayOf(VERSION_LEGACY) + sealed

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

    /** The index of the slot that [vaultKey] names, or -1 if none does. A legacy file names no slot. */
    fun ownerIndex(contents: Contents, vaultKey: ByteArray): Int {
        if (contents.legacy) return -1
        return contents.slots.indexOfFirst { slot ->
            val nonce = slot.copyOfRange(0, NONCE_BYTES)
            val tag = slot.copyOfRange(NONCE_BYTES, NONCE_BYTES + TAG_BYTES)
            MessageDigest.isEqual(tag, tagOf(vaultKey, nonce))
        }
    }

    private fun tagOf(vaultKey: ByteArray, nonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(vaultKey, "HmacSHA256")) }
        return mac.doFinal(TAG_LABEL + nonce).copyOf(TAG_BYTES)
    }
}
