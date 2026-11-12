package app.lensly.core.vault

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey

/** The key file of a wallet folder. It holds the wallet's Per-Wallet Key, wrapped under the vault key that owns the wallet. */
internal const val WALLET_KEY_FILE_NAME = "pwk.bin"

/** The fewest wallet folders a padded store holds, and the step its folder count grows by. */
internal const val WALLET_SLOT_STEP = 16

/** Names the file that lists the unused wallet slots. It sits outside `wallets/`, so it is never a wallet folder. */
internal const val WALLET_PAD_FILE_NAME = "wallet-pad.bin"

/** The section of [WALLET_PAD_FILE_NAME] that the real vault writes. */
internal const val REAL_SECTION = 0

/** The section of [WALLET_PAD_FILE_NAME] that the Decoy Vault writes. */
internal const val DECOY_SECTION = 1

/** The most wallet folders one vault's free list can name. A store holds at most this many folders. */
internal const val PAD_CAPACITY = 1024

/** The name prefix of a Review Record. Records with this prefix live in the pool, see [ReviewPool]. */
internal const val REVIEW_RECORD_PREFIX = "review-"

private const val WALLET_KEY_BYTES = 32
private const val BLOB_VERSION: Byte = 1
private const val WALLET_ID_BYTES = 36
private val WALLET_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** The plain list of one section: a count, then [PAD_CAPACITY] folder ids, unused entries zeroed. */
private const val PAD_PLAIN_BYTES = 4 + PAD_CAPACITY * WALLET_ID_BYTES

/** One section of the pad file: IV, the sealed list, and the tag. */
private const val PAD_SECTION_BYTES = Aead.IV_BYTES + PAD_PLAIN_BYTES + 16

/** The pad file: one section for the real vault, then one for the Decoy Vault. Its size never changes. */
private const val PAD_FILE_BYTES = 2 * PAD_SECTION_BYTES

/** The layout that the previous build wrote: one blob sealed under the hardware key, a version byte, a count, the ids. */
private const val LEGACY_PAD_VERSION: Byte = 1
private const val LEGACY_PAD_HEADER_BYTES = 5
private val LEGACY_PAD_AAD = "lensly.vault.wallet-pad".toByteArray(StandardCharsets.US_ASCII)

/**
 * File names of vault records. A record's file is named by a keyed hash of its name under the vault key, so the name shows
 * neither the record's name nor its vault. The real vault and the decoy vault keep their records apart in one folder.
 */
internal object VaultRecordNames {
    /**
     * The record names the app writes. Removing a Decoy Vault keeps the files of these names under the real key and deletes
     * the other files. A new vault record name must be added here.
     */
    val KNOWN: List<String> = listOf("settings")

    /** A record name the app can write. */
    val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    private val HASHED_FILE = Regex("[0-9a-f]{64}\\.bin")

    /** The file name of record [name] under [vaultKey]. */
    fun fileName(vaultKey: ByteArray, name: String): String {
        val digest = labelledHmac(vaultKey, "lensly.vault.record-name\u0000$name")
        return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) } + ".bin"
    }

    /**
     * The record name that [fileName] had before names were hashed, or null when the file is not such a file. A file that is
     * already named under the scheme is not one.
     */
    fun legacyNameOf(fileName: String): String? {
        if (!fileName.endsWith(".bin") || HASHED_FILE.matches(fileName)) return null
        return fileName.removeSuffix(".bin").takeIf { NAME.matches(it) }
    }
}

/**
 * True when wallet [walletId] has a key file that [vaultKey] unwraps. A wallet of the other vault, or a damaged one, is not
 * owned, so a session neither lists it nor deletes it.
 */
internal fun ownsWallet(layout: VaultLayout, vaultKey: ByteArray, walletId: String): Boolean {
    val file = File(layout.walletDir(walletId), WALLET_KEY_FILE_NAME)
    if (!file.isFile) return false
    return try {
        // The Per-Wallet Key is only checked here, so it is zeroed at once.
        Aead.open(vaultKey, walletId.toByteArray(StandardCharsets.UTF_8), decodeBlob(file.readBytes())).fill(0)
        true
    } catch (e: AEADBadTagException) {
        false
    } catch (e: VaultException) {
        false
    } catch (e: IOException) {
        false
    }
}

/**
 * Keeps the number of wallet folders constant. A store holds [WALLET_SLOT_STEP] folders or a multiple of it, and every
 * folder holds either one vault's wallet or an unused slot. An unused slot looks like a wallet whose key fails to open: its
 * files have the same names and sizes as a real wallet's, but their bytes are random.
 *
 * Each vault keeps its own list of unused slots, in its own section of [WALLET_PAD_FILE_NAME]. A section is sealed under a key
 * derived from that vault's key, so the Decoy Vault cannot read the real list, and the real list does not reveal the decoy's.
 * The file has two sections of fixed size whatever the folder count, so its size says nothing about either vault.
 *
 * A wallet takes an unused slot over, and deleting a wallet turns its slot back into an unused one, so the folder count
 * never says how many wallets exist. When every slot is used, the store grows by [WALLET_SLOT_STEP].
 */
internal class WalletPad(
    private val layout: VaultLayout,
    vaultKey: ByteArray,
    /** The section this vault writes: [REAL_SECTION] or [DECOY_SECTION]. */
    private val section: Int,
) {
    private val random = SecureRandom()
    private val file = File(layout.root, WALLET_PAD_FILE_NAME)

    /** This vault's key for its section. It is derived from the vault key, so the other vault's key cannot open it. */
    private val padKey: ByteArray = labelledHmac(vaultKey, "lensly.vault.wallet-pad")

    private fun aad(): ByteArray = "lensly.vault.wallet-pad\u0000$section".toByteArray(StandardCharsets.US_ASCII)

    /** Zeroes the pad key. After this the section reads as no unused slot, which is safe. */
    fun close() {
        Arrays.fill(padKey, 0)
    }

    /** Pads the store to its size for the wallets it holds, if it is short. Best effort, so an unlock still opens. */
    fun ensure() {
        try {
            val free = freeSlots()
            val used = folderDirs().size - free.size
            padTo(roundUp(maxOf(WALLET_SLOT_STEP, used)))
        } catch (e: VaultException) {
            // A pad that cannot be written leaves the wallets alone. The next unlock tries again.
        } catch (e: IOException) {
            // Same as above.
        } catch (e: GeneralSecurityException) {
            // Same as above.
        } catch (e: ProviderException) {
            // Same as above.
        }
    }

    /** Takes one unused slot and returns its id, for a new wallet to write into. Grows the store first if none is free. */
    fun take(): String? {
        var free = freeSlots()
        if (free.isEmpty()) {
            val used = folderDirs().size
            padTo(roundUp(maxOf(WALLET_SLOT_STEP, used + 1)))
            free = freeSlots()
        }
        val id = free.minOrNull() ?: return null
        // The slot stops being free before it is written, so a crash cannot make a real wallet look unused.
        write(free - id)
        return id
    }

    /** Turns the wallet folders [ids] into unused slots, overwriting their files with random bytes of the same sizes. */
    fun release(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val free = freeSlots()
        val turned = ids.filter { it !in free }
        if (turned.isEmpty()) return
        val dirs = folderDirs()
        turned.forEach { id -> overwriteAsFree(layout.walletDir(id)) }
        write(free + turned.filter { id -> dirs.any { it.name == id } })
    }

    /**
     * Gives up to [count] unused slots of this section to [other], another vault's section. The Duress setup uses it, so a new
     * Decoy Vault starts with slots of its own and the store does not grow for it. The slots leave this section first, so a crash
     * cannot leave one in both lists.
     */
    fun lend(other: WalletPad, count: Int) {
        val free = freeSlots()
        val given = free.sorted().take(count).toSet()
        if (given.isEmpty()) return
        write(free - given)
        other.receive(given)
    }

    private fun receive(ids: Set<String>) {
        write(freeSlots() + ids)
    }

    /** Makes the store hold [target] folders, adding unused ones to this vault's list. */
    fun padTo(target: Int) {
        val dirs = folderDirs()
        if (dirs.size >= target) return
        val free = freeSlots()
        val added = mutableSetOf<String>()
        repeat(target - dirs.size) {
            val id = UUID.randomUUID().toString()
            val dir = layout.walletDir(id)
            dir.mkdirs()
            writeFreeKeyFile(dir)
            added += id
        }
        write(free + added)
    }

    /** Removes every file of the taken slot, so no record of its last wallet can be read through the new one. */
    fun clearForWallet(id: String) {
        val dir = layout.walletDir(id)
        dir.listFiles()?.forEach { it.deleteRecursively() }
        dir.mkdirs()
    }

    /**
     * Moves the free-slot list that the previous build sealed under the hardware key into this vault's section. Each vault does
     * this on its first unlock after the upgrade, so the list is kept whichever vault unlocks first. A file already in the
     * current layout is left alone.
     */
    fun migrateLegacy(hardwareKey: SecretKey) {
        if (!file.isFile || file.length() == PAD_FILE_BYTES.toLong()) return
        val plain = try {
            Aead.openWithKeystore(hardwareKey, LEGACY_PAD_AAD, file.readBytes())
        } catch (e: GeneralSecurityException) {
            return
        } catch (e: ProviderException) {
            return
        }
        try {
            if (plain.size < LEGACY_PAD_HEADER_BYTES || plain[0] != LEGACY_PAD_VERSION) return
            val count = ByteBuffer.wrap(plain, 1, 4).int
            if (count < 0 || plain.size != LEGACY_PAD_HEADER_BYTES + count * WALLET_ID_BYTES) return
            val ids = (0 until count)
                .map { index -> String(plain, LEGACY_PAD_HEADER_BYTES + index * WALLET_ID_BYTES, WALLET_ID_BYTES, StandardCharsets.US_ASCII) }
                .filter { id -> id.any { it != '\u0000' } }
            write(ids.toSet())
        } finally {
            plain.fill(0)
        }
    }

    private fun roundUp(size: Int): Int {
        var target = WALLET_SLOT_STEP
        while (target < size) target += WALLET_SLOT_STEP
        return target
    }

    private fun folderDirs(): List<File> =
        layout.wallets.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()

    /** The file, or null when it is missing or not in the current layout. */
    private fun readFile(): ByteArray? =
        if (file.isFile && file.length() == PAD_FILE_BYTES.toLong()) file.readBytes() else null

    /** The free-slot list this section holds, as this vault's key reads it. For tests only. */
    internal fun freeSlotsForTesting(): Set<String> = freeSlots()

    /** The unused slots of this vault that still have a folder. A missing or damaged list reads as no unused slot, which is safe. */
    private fun freeSlots(): Set<String> {
        val bytes = readFile() ?: return emptySet()
        val plain = try {
            Aead.open(padKey, aad(), bytes.copyOfRange(section * PAD_SECTION_BYTES, (section + 1) * PAD_SECTION_BYTES))
        } catch (e: GeneralSecurityException) {
            return emptySet()
        }
        try {
            val count = ByteBuffer.wrap(plain, 0, 4).int
            if (count !in 0..PAD_CAPACITY) return emptySet()
            val dirs = folderDirs().map { it.name }.toSet()
            return (0 until count)
                .map { index -> String(plain, 4 + index * WALLET_ID_BYTES, WALLET_ID_BYTES, StandardCharsets.US_ASCII) }
                .filter { it in dirs }
                .toSet()
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Seals [free] as this vault's unused-slot list and writes the file with the other section kept byte for byte. The file has a
     * fixed size, so a write never changes what the storage shows about the store.
     */
    private fun write(free: Set<String>) {
        val dirs = folderDirs().map { it.name }.toSet()
        val ids = free.filter { it in dirs }.sorted().take(PAD_CAPACITY)
        val plain = ByteArray(PAD_PLAIN_BYTES)
        try {
            ByteBuffer.wrap(plain, 0, 4).putInt(ids.size)
            ids.forEachIndexed { index, id -> id.toByteArray(StandardCharsets.US_ASCII).copyInto(plain, 4 + index * WALLET_ID_BYTES) }
            val mine = try {
                Aead.seal(padKey, aad(), plain)
            } catch (e: GeneralSecurityException) {
                throw VaultException("The wallet slots could not be saved.", e)
            } catch (e: ProviderException) {
                throw VaultException("The wallet slots could not be saved.", e)
            }
            val theirs = 1 - section
            val other = readFile()?.copyOfRange(theirs * PAD_SECTION_BYTES, (theirs + 1) * PAD_SECTION_BYTES)
                ?: ByteArray(PAD_SECTION_BYTES).also { random.nextBytes(it) }
            writeAtomically(file, if (section == REAL_SECTION) mine + other else other + mine)
        } finally {
            plain.fill(0)
        }
    }

    /** Overwrites every file of [dir] with random bytes of the same size, then writes an unopenable key file. */
    private fun overwriteAsFree(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { child ->
            if (child.isFile) overwriteInPlace(child) else child.deleteRecursively()
        }
        writeFreeKeyFile(dir)
    }

    /** A key file of the size a real one has, with the version byte kept so opening it costs the same. */
    private fun writeFreeKeyFile(dir: File) {
        dir.mkdirs()
        val bytes = byteArrayOf(BLOB_VERSION) + ByteArray(WALLET_KEY_BYTES + Aead.IV_BYTES + 16).also { random.nextBytes(it) }
        // No sync: a slot is padding, and a half-written one is still an empty slot. The pad list is the durable record.
        File(dir, WALLET_KEY_FILE_NAME).writeBytes(bytes)
    }
}

/**
 * An unlocked vault. It holds the Vault Key in memory until [close] is called.
 *
 * Each wallet has a random Per-Wallet Key, wrapped under the Vault Key with AAD = wallet id, in `pwk.bin`. Each Review Record is
 * sealed under its wallet's key in the shared [ReviewPool], and every other record is a file in the wallet's folder, sealed the
 * same way with AAD = wallet id + NUL + record name. [deleteWallet] removes `pwk.bin` first, which shreds the wallet even if
 * removing its files fails part way.
 *
 * A session is either the real vault or the Decoy Vault ([isDecoy]). Both vaults keep their wallets and records in the same
 * folders and pool, but a session lists, reads and deletes only the files its own key opens. A wallet of the other vault is treated
 * as absent. A decoy session therefore cannot see, change, or delete any real wallet, and the reverse holds too.
 *
 * A padded store keeps a fixed number of wallet folders, so the folder count never says how many wallets exist. Unused
 * folders look like wallets; see [WalletPad].
 *
 * Record bytes belong to the caller. The session does not zero the array passed to [putRecord], and the array returned by
 * [getRecord] is the caller's to zero.
 *
 * Methods are synchronized on the session, so they can be called from any thread.
 */
class VaultSession internal constructor(
    private val layout: VaultLayout,
    vaultKey: ByteArray,
    /** True for the Decoy Vault, which a Duress Password opens. The App Password never opens it. */
    val isDecoy: Boolean = false,
    /** This vault's unused wallet slots, or null when the folder count is not padded (tests and plain folders). */
    private val pad: WalletPad? = null,
) {
    private var vaultKey: ByteArray? = vaultKey

    /** Wallet ids this session can open, sorted. Another vault's wallets are not listed. */
    fun listWallets(): List<String> = synchronized(this) {
        val key = requireOpen()
        layout.wallets.list()
            ?.filter { ownsWallet(layout, key, it) }
            ?.sorted()
            ?: emptyList()
    }

    /** Creates a wallet with a fresh Per-Wallet Key and returns its id. A padded store writes it into an unused slot. */
    fun createWallet(): String = synchronized(this) {
        val key = requireOpen()
        val unused = pad?.take()
        val id = unused ?: UUID.randomUUID().toString()
        val dir = layout.walletDir(id)
        if (unused != null) pad.clearForWallet(id) else dir.mkdirs()
        val walletKey = ByteArray(WALLET_KEY_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            val wrapped = Aead.seal(key, id.toByteArray(StandardCharsets.UTF_8), walletKey)
            writeAtomically(walletKeyFile(id), encodeBlob(wrapped))
        } finally {
            Arrays.fill(walletKey, 0)
        }
        id
    }

    /**
     * Stores [bytes] as record [name] of wallet [walletId], replacing any earlier value.
     *
     * @throws IllegalArgumentException if the id is malformed, the name is invalid or reserved, or the wallet does not exist.
     */
    fun putRecord(walletId: String, name: String, bytes: ByteArray): Unit = synchronized(this) {
        val key = requireOpen()
        requireWalletId(walletId)
        requireRecordName(name)
        val walletKey = unwrapWalletKey(key, walletId)
            ?: throw IllegalArgumentException("No wallet with id $walletId.")
        try {
            val sealed = Aead.seal(walletKey, recordAad(walletId, name), bytes)
            writeAtomically(recordFile(walletId, name), encodeBlob(sealed))
        } finally {
            Arrays.fill(walletKey, 0)
        }
    }

    /** The record's bytes, or null if the wallet or the record does not exist (including after [deleteWallet]). */
    fun getRecord(walletId: String, name: String): ByteArray? {
        synchronized(this) {
            val key = requireOpen()
            requireWalletId(walletId)
            requireRecordName(name)
            val file = recordFile(walletId, name)
            if (!file.isFile) return null
            val walletKey = unwrapWalletKey(key, walletId) ?: return null
            try {
                return Aead.open(walletKey, recordAad(walletId, name), decodeBlob(file.readBytes()))
            } catch (e: AEADBadTagException) {
                throw VaultException("Record '$name' of wallet $walletId failed authentication.", e)
            } finally {
                Arrays.fill(walletKey, 0)
            }
        }
    }

    /**
     * The names of the records of wallet [walletId] that start with [prefix], sorted. A wallet this session cannot open has
     * none, so neither vault lists the other's records.
     *
     * @throws IllegalArgumentException if the id is malformed.
     */
    fun recordNames(walletId: String, prefix: String): List<String> {
        synchronized(this) {
            val key = requireOpen()
            requireWalletId(walletId)
            if (!ownsWallet(layout, key, walletId)) return emptyList()
            return layout.walletDir(walletId).list().orEmpty()
                .filter { it.startsWith(prefix) && it.endsWith(".bin") }
                .map { it.removeSuffix(".bin") }
                .sorted()
        }
    }

    /** Removes record [name] of wallet [walletId]. Does nothing if the record does not exist. */
    fun deleteRecord(walletId: String, name: String): Unit = synchronized(this) {
        requireOpen()
        requireWalletId(walletId)
        requireRecordName(name)
        Files.deleteIfExists(recordFile(walletId, name).toPath())
    }

    /**
     * Applies several record changes to wallet [walletId] as one write: each record in [puts] is stored, and each name in
     * [deletes] is removed. The session lock is held throughout, and nothing is written unless every name is valid and
     * every record seals. If a file cannot be written part way, the files already changed are put back, so either all of
     * the changes land or none does. A crash in the middle of the writes can still leave some of them, since a
     * filesystem commits one file at a time.
     *
     * @throws IllegalArgumentException if the id or a name is malformed or reserved, a name is both put and deleted, or the
     * wallet does not exist.
     */
    fun putRecords(walletId: String, puts: Map<String, ByteArray>, deletes: Collection<String>): Unit = synchronized(this) {
        val key = requireOpen()
        requireWalletId(walletId)
        (puts.keys + deletes).forEach { requireRecordName(it) }
        require(puts.keys.none { it in deletes }) { "A record cannot be stored and removed in one write." }
        val walletKey = unwrapWalletKey(key, walletId)
            ?: throw IllegalArgumentException("No wallet with id $walletId.")
        // Every record is sealed before any file changes, so a failure here writes nothing.
        val sealed = try {
            puts.map { (name, bytes) ->
                recordFile(walletId, name) to encodeBlob(Aead.seal(walletKey, recordAad(walletId, name), bytes))
            }
        } finally {
            Arrays.fill(walletKey, 0)
        }
        val targets = (sealed.map { it.first } + deletes.map { recordFile(walletId, it) }).distinct()
        // What each target holds now, so a failed write can put it back. Null means the record does not exist.
        val before = targets.associateWith { file -> if (file.isFile) file.readBytes() else null }
        try {
            sealed.forEach { (file, blob) -> writeAtomically(file, blob) }
            deletes.forEach { name -> Files.deleteIfExists(recordFile(walletId, name).toPath()) }
        } catch (e: Throwable) {
            restore(before, e)
            throw e
        }
    }

    /** Puts back what [before] recorded for each file. A file that could not be put back is added to [failure]. */
    private fun restore(before: Map<File, ByteArray?>, failure: Throwable) {
        before.forEach { (file, bytes) ->
            try {
                if (bytes == null) Files.deleteIfExists(file.toPath()) else writeAtomically(file, bytes)
            } catch (e: Throwable) {
                failure.addSuppressed(e)
            }
        }
    }

    /**
     * Stores [bytes] as Review Record [name] of wallet [walletId] in the shared pool, replacing any earlier record of that name.
     *
     * @throws IllegalArgumentException if the id or the name is malformed, the wallet does not exist, or the record is too large
     * for one pool slot.
     */
    fun putReview(walletId: String, name: String, bytes: ByteArray): Unit = synchronized(this) {
        val key = requireOpen()
        requireWalletId(walletId)
        requireRecordName(name)
        val walletKey = unwrapWalletKey(key, walletId)
            ?: throw IllegalArgumentException("No wallet with id $walletId.")
        try {
            ReviewPool(layout.reviewPool, key).put(walletId, walletKey, name, bytes)
        } finally {
            Arrays.fill(walletKey, 0)
        }
    }

    /** The Review Record [name] of wallet [walletId], or null if there is none. The caller zeroes the array. */
    fun getReview(walletId: String, name: String): ByteArray? = synchronized(this) {
        val key = requireOpen()
        requireWalletId(walletId)
        requireRecordName(name)
        val pool = ReviewPool(layout.reviewPool, key)
        // A record with no slot is absent. Checking that first means a deleted wallet's key file is never needed to say so.
        if (pool.fileOf(walletId, name) == null) return@synchronized null
        val walletKey = unwrapWalletKey(key, walletId) ?: return@synchronized null
        try {
            pool.get(walletId, walletKey, name)
        } finally {
            Arrays.fill(walletKey, 0)
        }
    }

    /**
     * The Review Records of wallet [walletId] with their names, sorted by name, read in one pass over the pool. A record that cannot
     * be opened is left out. The caller zeroes the arrays. A wallet this session cannot open has none.
     */
    fun reviewRecords(walletId: String): List<Pair<String, ByteArray>> = synchronized(this) {
        val key = requireOpen()
        requireWalletId(walletId)
        if (!ownsWallet(layout, key, walletId)) return@synchronized emptyList()
        val pool = ReviewPool(layout.reviewPool, key)
        val slots = pool.slotsOf(walletId)
        if (slots.isEmpty()) return@synchronized emptyList()
        val walletKey = unwrapWalletKey(key, walletId) ?: return@synchronized emptyList()
        try {
            slots.toSortedMap().mapNotNull { (name, file) ->
                try {
                    name to pool.readSlot(walletKey, walletId, name, file)
                } catch (e: VaultException) {
                    null
                }
            }
        } finally {
            Arrays.fill(walletKey, 0)
        }
    }

    /** The names of the Review Records of wallet [walletId], sorted. A wallet this session cannot open has none. */
    fun reviewNames(walletId: String): List<String> = synchronized(this) {
        val key = requireOpen()
        requireWalletId(walletId)
        if (!ownsWallet(layout, key, walletId)) return@synchronized emptyList()
        ReviewPool(layout.reviewPool, key).names(walletId)
    }

    /**
     * Crypto-shreds a wallet: removes its wrapped Per-Wallet Key first, then its record files and its Review Records in the pool.
     * Does nothing if the wallet does not exist, or if this session cannot open it. A wallet of the other vault is never deleted
     * from here.
     */
    fun deleteWallet(walletId: String) {
        synchronized(this) {
            val key = requireOpen()
            requireWalletId(walletId)
            val dir = layout.walletDir(walletId)
            if (!dir.isDirectory) return
            if (!ownsWallet(layout, key, walletId)) return
            Files.deleteIfExists(walletKeyFile(walletId).toPath())
            ReviewPool(layout.reviewPool, key).release(walletId)
            if (pad != null) pad.release(listOf(walletId)) else dir.deleteRecursively()
        }
    }

    /**
     * Stores [bytes] as the vault record [name], replacing any earlier value. A vault record belongs to the vault, not to a
     * wallet, so it holds no wallet data: a setting, for example. It is encrypted under the Vault Key itself, with its own
     * AAD, and its file name is keyed by the Vault Key (see [VaultRecordNames]). [Vault.rekey] keeps the Vault Key, so the
     * record stays readable after a password change, and [Vault.wipe] removes it.
     *
     * @throws IllegalArgumentException if the name is invalid.
     */
    fun putVaultRecord(name: String, bytes: ByteArray): Unit = synchronized(this) {
        val key = requireOpen()
        requireRecordName(name)
        layout.vaultRecords.mkdirs()
        writeAtomically(vaultRecordFile(key, name), encodeBlob(Aead.seal(key, vaultRecordAad(name), bytes)))
    }

    /** The vault record [name], or null if it has never been stored. */
    fun getVaultRecord(name: String): ByteArray? = synchronized(this) {
        val key = requireOpen()
        requireRecordName(name)
        val file = vaultRecordFile(key, name)
        if (!file.isFile) return@synchronized null
        try {
            Aead.open(key, vaultRecordAad(name), decodeBlob(file.readBytes()))
        } catch (e: AEADBadTagException) {
            throw VaultException("Vault record '$name' failed authentication.", e)
        }
    }

    /**
     * Renames vault records written under plain names, which the app used before names were keyed. A file keeps its contents:
     * the record name is bound into its AAD, not into its file name. A file that cannot be moved stays where it is, and the
     * next unlock tries again.
     */
    internal fun migrateLegacyRecordNames(): Unit = synchronized(this) {
        val key = requireOpen()
        layout.vaultRecords.listFiles()?.forEach { file ->
            val name = VaultRecordNames.legacyNameOf(file.name) ?: return@forEach
            val target = vaultRecordFile(key, name)
            if (target.exists()) return@forEach
            try {
                Files.move(file.toPath(), target.toPath())
            } catch (e: IOException) {
                // Left in place. The next unlock tries again.
            }
        }
    }

    /**
     * Moves the Review Records that earlier builds kept in wallet folders into the pool, then overwrites and deletes each original.
     * A record already in the pool is not stored twice, so an interrupted move is finished by the next unlock. A record that cannot
     * be read is shredded, since it is lost either way. A record too large for a pool slot stays where it is.
     */
    internal fun migrateLegacyReviews(): Unit = synchronized(this) {
        val key = requireOpen()
        val pool = ReviewPool(layout.reviewPool, key)
        for (walletId in listWallets()) {
            val legacy = layout.walletDir(walletId).listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith(REVIEW_RECORD_PREFIX) && it.name.endsWith(".bin") }
            if (legacy.isEmpty()) continue
            val walletKey = unwrapWalletKey(key, walletId) ?: continue
            try {
                val stored = pool.names(walletId).toSet()
                legacy.forEach { file ->
                    migrateLegacyReview(pool, walletId, walletKey, file, file.name.removeSuffix(".bin") in stored)
                }
            } finally {
                Arrays.fill(walletKey, 0)
            }
        }
    }

    private fun migrateLegacyReview(pool: ReviewPool, walletId: String, walletKey: ByteArray, file: File, alreadyStored: Boolean) {
        val name = file.name.removeSuffix(".bin")
        val bytes = try {
            Aead.open(walletKey, recordAad(walletId, name), decodeBlob(file.readBytes()))
        } catch (e: AEADBadTagException) {
            null
        } catch (e: VaultException) {
            null
        }
        if (bytes == null) {
            overwriteInPlace(file)
            file.delete()
            return
        }
        try {
            if (!alreadyStored) pool.put(walletId, walletKey, name, bytes)
        } catch (e: IllegalArgumentException) {
            return
        } catch (e: VaultException) {
            return
        } catch (e: IOException) {
            return
        } finally {
            Arrays.fill(bytes, 0)
        }
        overwriteInPlace(file)
        file.delete()
    }

    /** Moves the free-slot list of an earlier build into this vault's pad section. See [WalletPad.migrateLegacy]. */
    internal fun migrateLegacyPad(hardwareKey: SecretKey): Unit = synchronized(this) {
        requireOpen()
        pad?.migrateLegacy(hardwareKey)
    }

    /** Creates the pool's first batch if this vault has none, so an empty vault still shows a full pool. */
    internal fun ensureReviewPool(): Unit = synchronized(this) {
        ReviewPool(layout.reviewPool, requireOpen()).ensureFirstBatch()
    }

    /** Pads the wallet folders to the store's size for the wallets it holds. Best effort, called on unlock. */
    internal fun ensurePadded() {
        pad?.ensure()
    }

    /** Zeroes the Vault Key and the pad key. Any later call on this session throws [IllegalStateException]. */
    fun close() {
        synchronized(this) {
            vaultKey?.let { Arrays.fill(it, 0) }
            vaultKey = null
            pad?.close()
        }
    }

    /** A copy of the Vault Key, for [Vault.rekey] and the Duress Password operations. The caller zeroes it. */
    internal fun copyOfVaultKey(): ByteArray = synchronized(this) { requireOpen().copyOf() }

    /** The file that holds vault record [name] for this session. For tests only. */
    internal fun vaultRecordFileForTesting(name: String): File = synchronized(this) {
        vaultRecordFile(requireOpen(), name)
    }

    /** The pool file that holds Review Record [name] of wallet [walletId]. For tests only. */
    internal fun reviewFileForTesting(walletId: String, name: String): File? = synchronized(this) {
        ReviewPool(layout.reviewPool, requireOpen()).fileOf(walletId, name)
    }

    private fun requireOpen(): ByteArray = vaultKey ?: throw IllegalStateException("The VaultSession is closed.")

    private fun walletKeyFile(walletId: String): File = File(layout.walletDir(walletId), WALLET_KEY_FILE_NAME)

    private fun recordFile(walletId: String, name: String): File =
        File(layout.walletDir(walletId), "$name.bin")

    private fun vaultRecordFile(vaultKey: ByteArray, name: String): File =
        File(layout.vaultRecords, VaultRecordNames.fileName(vaultKey, name))

    /** Returns the Per-Wallet Key, or null if the wallet has no key file. The caller zeroes the result. */
    private fun unwrapWalletKey(vaultKey: ByteArray, walletId: String): ByteArray? {
        val file = walletKeyFile(walletId)
        if (!file.isFile) return null
        return try {
            Aead.open(vaultKey, walletId.toByteArray(StandardCharsets.UTF_8), decodeBlob(file.readBytes()))
        } catch (e: AEADBadTagException) {
            throw VaultException("The key of wallet $walletId failed authentication.", e)
        }
    }

    private companion object {
        fun requireWalletId(id: String) {
            require(WALLET_ID.matches(id)) { "Malformed wallet id." }
        }

        fun requireRecordName(name: String) {
            // "pwk" is reserved: a record of that name would overwrite the wallet's key file.
            require(VaultRecordNames.NAME.matches(name) && name != "pwk") { "Invalid record name." }
        }

        fun recordAad(walletId: String, name: String): ByteArray =
            (walletId + "\u0000" + name).toByteArray(StandardCharsets.UTF_8)

        /** A UUID never starts with "vault-record", so these AADs cannot collide with a wallet record's. */
        fun vaultRecordAad(name: String): ByteArray =
            ("vault-record\u0000" + name).toByteArray(StandardCharsets.UTF_8)
    }
}

private fun encodeBlob(sealed: ByteArray): ByteArray = byteArrayOf(BLOB_VERSION) + sealed

private fun decodeBlob(bytes: ByteArray): ByteArray {
    if (bytes.size < 1 + Aead.IV_BYTES + 16 || bytes[0] != BLOB_VERSION) {
        throw VaultException("A wallet file is damaged or not in a format this app reads.")
    }
    return bytes.copyOfRange(1, bytes.size)
}
