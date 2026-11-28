package app.lensly.core.vault

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import javax.crypto.AEADBadTagException

/** The fewest wallet folders a padded store holds, and the step its folder count grows by. */
internal const val WALLET_SLOT_STEP = 16

/** Names the file that lists the unused wallet slots. It sits outside `items/`, so it is never a wallet folder. */
internal const val WALLET_PAD_FILE_NAME = "spare.bin"

/** The most wallet folders one vault's free list can name. A store holds at most this many folders. */
internal const val PAD_CAPACITY = 1024

/** The name prefix of a Review Record. Records with this prefix live in the pool, see [ReviewPool]. */
const val REVIEW_RECORD_PREFIX = "review-"

private const val WALLET_KEY_BYTES = 32

/** The length of a key from [VaultSession.deriveKey]. */
private const val DERIVED_KEY_BYTES = 32
internal const val BLOB_VERSION: Byte = 1
private const val WALLET_ID_BYTES = 36
private val WALLET_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** The plain list of one section: a count, then [PAD_CAPACITY] folder ids, unused entries zeroed. */
private const val PAD_PLAIN_BYTES = 4 + PAD_CAPACITY * WALLET_ID_BYTES

/** One section of the pad file: IV, the sealed list, and the tag. */
private const val PAD_SECTION_BYTES = Aead.IV_BYTES + PAD_PLAIN_BYTES + 16

/** The pad file: one section for each side (see [VaultRole]). Its size never changes. */
private const val PAD_FILE_BYTES = 2 * PAD_SECTION_BYTES

/**
 * File names of vault records. A vault's file is named by a keyed hash of its name under that vault's key, so the name shows neither the
 * record's name nor its vault, and no name can be computed without a key. A new vault gets twice as many files as there are record names:
 * one set under its own key, and a set of spares under random names. A Decoy Vault takes the spares over by renaming them to names under
 * its key, and gives them up as spares again when it goes (see [Vault]), so a Decoy Vault never adds a file to the folder. Every file
 * holds sealed or random bytes of the same kind, so the folder shows neither which files are written nor whether a decoy exists.
 *
 * Every file is exactly one size class long: [CLASS_BYTES] (256 B, 1 KiB, 4 KiB, 16 KiB), or a multiple of the largest class for a record
 * that holds more. A file of a name starts in the class that fits the most that record is expected to hold ([FILE_BYTES]), as a placeholder
 * and as a spare, and writing a value keeps the file's size unless the value does not fit, so a written file stays in the class of its
 * placeholder. The padding sits inside the sealed bytes (see [valueOf]).
 */
internal object VaultRecordNames {
    /**
     * The record names the apps write, each with the size of its file when it holds no more than expected. Removing a Decoy Vault keeps
     * the files of these names under the real key and deletes the other files, and a wipe deletes these files. A new vault record name
     * must be added here, in the smallest size class that holds what it usually stores (a record that outgrows its class moves up):
     * [VaultSession.putVaultRecord] and [VaultSession.getVaultRecord] refuse any other name.
     */
    private val FILE_BYTES: Map<String, Int> = mapOf(
        // Under 100 bytes: a flag, a few codes, a key.
        PHOTO_KEY to 256, "lumio-network-mode" to 256, "lumio-prefer-rpc" to 256,
        // A few hundred bytes: settings, option records and the setup checklist.
        "settings" to 1024, "lumio-display-settings" to 1024, "lumio-evm-settings" to 1024, "lumio-finish-setup" to 1024,
        "lumio-providers" to 1024, "lumio-transaction-settings" to 1024,
        // Lists that the owner grows: dozens of entries.
        "address-book" to 4096, "lumio-address-books" to 4096, "lumio-custom-tokens" to 4096, "lumio-watchlist" to 4096,
        // Caches that hold one read of about 200 bytes for every address of every paired wallet on every chain.
        "lumio-balance-cache" to 16384, "lumio-native-balance-cache" to 16384, "lumio-token-balances" to 16384,
    )

    val KNOWN: List<String> = FILE_BYTES.keys.toList()

    /** The size of the file of record [name] while it holds no more than expected. */
    fun fileBytesOf(name: String): Int = FILE_BYTES.getValue(name)

    /** The sizes a record file can have, up to the largest class. A larger file is a multiple of the largest class. */
    private val CLASS_BYTES = intArrayOf(256, 1024, 4096, 16384)

    /** The smallest file size, in bytes, that holds [bytes]: the first class that is as long, or the next multiple of the largest class. */
    fun classFor(bytes: Int): Int {
        val top = CLASS_BYTES.last()
        return CLASS_BYTES.firstOrNull { it >= bytes } ?: ((bytes + top - 1) / top * top)
    }

    /** The private half of a vault's photo key pair. See [VaultSession.ensurePhotoKey]. */
    const val PHOTO_KEY = "photo-key"

    private val FOLDER_LOCK = Any()

    /** Test hook: called inside a pass, after a file's bytes are read and before they are written back. */
    @Volatile
    internal var passHookForTesting: ((File) -> Unit)? = null

    /**
     * Runs [block] while no other thread writes, renames, deletes or rewrites a file in the records folder. Every such change goes through
     * it, so a pass (see [rewriteAll]) never puts back a stale copy, recreates a renamed file or resurrects a deleted one. It guards only
     * file operations, so no other lock is taken inside it.
     */
    fun <T> underFolderLock(block: () -> T): T = synchronized(FOLDER_LOCK) { block() }

    /**
     * Rewrites every record file in [dir] with its own bytes in one pass, in file name order, all with the same modification time. A file
     * that is written is replaced by a rename, so it gets a new inode, a new creation time and a new change time that the app cannot set.
     * Rewriting every file gives all of them fresh ones, so the folder does not show which file was written, or that a Decoy Vault wrote.
     * Each file is still replaced atomically, so a crash leaves it either old or new. Called after every write, create, claim, reset and
     * retire. It needs no key: it copies the sealed bytes. It also removes temp files that a crash left, which carry a record's hashed name.
     *
     * The change that came before is already saved, so a pass that fails (a full disk, an unreadable file) is not reported: the files it
     * did not reach keep their earlier times until the next write runs a pass.
     */
    fun rewriteAll(dir: File): Unit = underFolderLock {
        try {
            val files = dir.listFiles().orEmpty()
            files.filter { it.isFile && it.name.endsWith(".tmp") }.forEach { it.delete() }
            val time = FileTime.fromMillis(System.currentTimeMillis())
            files.filter { it.isFile && it.name.endsWith(".bin") }.sortedBy { it.name }.forEach { file ->
                val bytes = file.readBytes()
                passHookForTesting?.invoke(file)
                val temp = writeTemp(file, bytes)
                try {
                    Files.setLastModifiedTime(temp.toPath(), time)
                } catch (e: Throwable) {
                    temp.delete()
                    throw e
                }
                commitTemp(temp, file)
            }
        } catch (e: IOException) {
            // Best effort, as above.
        }
    }

    /** The file name of record [name] under [vaultKey]. */
    fun fileName(vaultKey: ByteArray, name: String): String = fileNameOf(labelledHmac(vaultKey, "lensly.vault.record-name\u0000$name"))

    /** A name that no key makes, of the same form as [fileName], for a spare file. */
    fun randomFileName(random: SecureRandom): String = fileNameOf(ByteArray(SHA256_BYTES).also { random.nextBytes(it) })

    private const val SHA256_BYTES = 32

    private fun fileNameOf(digest: ByteArray): String = digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) } + ".bin"

    /** A UUID never starts with "vault-record", so these AADs cannot collide with a wallet record's. */
    fun aad(name: String): ByteArray = ("vault-record\u0000" + name).toByteArray(StandardCharsets.UTF_8)

    private const val UNSET: Byte = 0
    private const val SET: Byte = 1

    /** The plain record: [UNSET] or [SET] (1), the value's length (4), the value, then zeros up to the end of the size class. */
    private const val HEADER_BYTES = 1 + 4

    /**
     * The record file of [name] with [value] sealed under [vaultKey]. The file is [floor] bytes long, or the next size class up when the
     * value does not fit. A write passes the size of the file it replaces, so a written record never shrinks out of its class.
     */
    fun sealed(vaultKey: ByteArray, name: String, value: ByteArray, floor: Int = fileBytesOf(name)): ByteArray {
        val plain = ByteArray(classFor(maxOf(floor, FILE_OVERHEAD_BYTES + HEADER_BYTES + value.size)) - FILE_OVERHEAD_BYTES)
        plain[0] = SET
        ByteBuffer.wrap(plain, 1, 4).putInt(value.size)
        value.copyInto(plain, HEADER_BYTES)
        return seal(vaultKey, name, plain)
    }

    /** A record file of [name] under [vaultKey] that holds no value and is [fileBytes] long, so a file that is reset keeps its size. */
    fun sealedUnset(vaultKey: ByteArray, name: String, fileBytes: Int = fileBytesOf(name)): ByteArray =
        seal(vaultKey, name, ByteArray(classFor(fileBytes) - FILE_OVERHEAD_BYTES))

    private fun seal(vaultKey: ByteArray, name: String, plain: ByteArray): ByteArray {
        try {
            return encodeBlob(Aead.seal(vaultKey, aad(name), plain))
        } finally {
            Arrays.fill(plain, 0)
        }
    }

    /** Blob version, IV and tag around the plaintext of a record file. */
    private const val FILE_OVERHEAD_BYTES = 1 + Aead.IV_BYTES + 16

    /**
     * The value in [plain], the opened plaintext of a record file, or null when the record has no value.
     *
     * @throws VaultException when the record is not whole: its size is not a size class, its flag or length is invalid, or a padding byte
     * is not zero. The padding is sealed with the value, so this holds only for a file that was sealed that way.
     */
    fun valueOf(plain: ByteArray): ByteArray? {
        val damaged = VaultException("A vault record is damaged or not in a format this application reads.")
        if (plain.size < HEADER_BYTES || classFor(plain.size + FILE_OVERHEAD_BYTES) != plain.size + FILE_OVERHEAD_BYTES) throw damaged
        val flag = plain[0]
        val length = ByteBuffer.wrap(plain, 1, 4).int
        if ((flag != SET && flag != UNSET) || length < 0 || (flag == UNSET && length != 0) || length > plain.size - HEADER_BYTES) throw damaged
        if ((HEADER_BYTES + length until plain.size).any { plain[it] != 0.toByte() }) throw damaged
        return if (flag == UNSET) null else plain.copyOfRange(HEADER_BYTES, HEADER_BYTES + length)
    }
}

/**
 * True when wallet [walletId] has a key file that [vaultKey] opens. A wallet of the other vault, or a damaged one, is not owned, so a
 * session neither lists it nor deletes it. A Keystore or read failure throws [VaultException] instead, since it is not a verdict on the
 * wallet, and a caller that deletes must not take it for one.
 */
internal fun ownsWallet(keys: KeystoreBackend, layout: VaultLayout, vaultKey: ByteArray, walletId: String): Boolean {
    val walletKey = WalletFolder.walletKeyOf(keys, layout.walletDir(walletId), vaultKey, walletId) ?: return false
    walletKey.fill(0)
    return true
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
class WalletPad(
    private val layout: VaultLayout,
    private val keys: KeystoreBackend,
    vaultKey: ByteArray,
    /** The section this vault writes: its side, 0 or 1 (see [VaultRole]). */
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

    /**
     * Pads the store to its size for the wallets it holds, and gives every unused slot of this vault the wallet folder shape. Best
     * effort, so an unlock still opens.
     */
    fun ensure() {
        try {
            val free = freeSlots()
            val used = folderDirs().size - free.size
            padTo(roundUp(maxOf(WALLET_SLOT_STEP, used)))
            for (id in freeSlots()) {
                val dir = layout.walletDir(id)
                if (!WalletFolder.isUnused(dir)) WalletFolder.makeUnused(dir, keys)
            }
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

    /**
     * Turns the wallet folders [ids] into unused slots. Each folder's key goes first and its files are overwritten with random bytes of the
     * same sizes (see [WalletFolder.makeUnused]). Returns false when a folder's key could not be deleted from the Keystore.
     */
    fun release(ids: Collection<String>): Boolean {
        if (ids.isEmpty()) return true
        // A slot that is listed as free is retired too: a folder a wallet was written into stays a wallet's until it is overwritten.
        val dirs = folderDirs()
        val erased = ids.map { id -> WalletFolder.makeUnused(layout.walletDir(id), keys) }
        write(freeSlots() + ids.filter { id -> dirs.any { it.name == id } })
        return erased.all { it }
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
            WalletFolder.makeUnused(layout.walletDir(id), keys)
            added += id
        }
        write(free + added)
    }

    /**
     * Readies a taken slot for a new wallet. [release] already made an unused slot random bytes of the folder shape, so only a slot
     * that is not in that shape is made so again. No record of its last wallet can be read through the new one.
     */
    fun clearForWallet(id: String) {
        val dir = layout.walletDir(id)
        if (!WalletFolder.isUnused(dir)) WalletFolder.makeUnused(dir, keys)
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

    /** The unused slots of this vault that still have a folder. A missing or damaged list reads as no unused slot, which is safe. */
    internal fun freeSlots(): Set<String> {
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
    internal fun write(free: Set<String>) {
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
            writeAtomically(file, if (section == 0) mine + other else other + mine)
        } finally {
            plain.fill(0)
        }
    }
}

/**
 * An unlocked vault. It holds the Vault Key in memory until [close] is called.
 *
 * Each wallet has a random Per-Wallet Key, sealed under the Vault Key and then under its folder's Keystore key, in the key file (see
 * [WalletFolder]). Each Review Record is sealed under its wallet's key in the shared [ReviewPool], and every other record is a file in
 * the wallet's folder, padded to a fixed size and sealed under the same key. [deleteWallet] deletes the folder's Keystore key first, which
 * shreds the wallet even if its files survive.
 *
 * A session is always the vault that its password opened, on one side ([side]). A vault and its Decoy Vault keep their wallets and
 * records in the same folders and pool, but a session lists, reads and deletes only the files its own key opens. A wallet of the other
 * vault is treated as absent.
 *
 * A padded store keeps a fixed number of wallet folders, so the folder count never says how many wallets exist. Unused
 * folders look like wallets; see [WalletPad].
 *
 * Record bytes belong to the caller. The session does not zero the array passed to [putRecord], and the array returned by
 * [getRecord] is the caller's to zero.
 *
 * Methods are synchronized on the session, so they can be called from any thread.
 */
class VaultSession constructor(
    private val layout: VaultLayout,
    private val keys: KeystoreBackend,
    vaultKey: ByteArray,
    /** The side of this vault, 0 or 1 (see [VaultRole]): its section of the wallet pad and its photo folder. */
    val side: Int = 0,
    /** This vault's unused wallet slots, or null when the folder count is not padded (tests and plain folders). */
    private val pad: WalletPad? = null,
) {
    private var vaultKey: ByteArray? = vaultKey

    /** The wallet ids this session owns, sorted, once a scan has checked every folder. Null until then, and after a wallet is created or deleted. */
    private var owned: List<String>? = null

    /**
     * Counts the writes that can change what a read returns: wallets, wallet records and Review Records. Each increment is made under the
     * session lock. [writeCount] reads it without the lock, so a caller on the main thread never waits for a write.
     */
    @Volatile private var writes = 0L

    /**
     * The number of writes made through this session so far that can change a read. A read taken at one count is current only while the
     * count is unchanged, so a caller that keeps such a read checks it against this.
     */
    val writeCount: Long get() = writes

    /** Wallet ids this session can open, sorted. Another vault's wallets are not listed, and neither is a folder the Keystore fails on. */
    fun listWallets(): List<String> = synchronized(this) {
        val key = requireOpen()
        owned ?: scanOwned(key)
    }

    /** Creates a wallet with a fresh Per-Wallet Key and returns its id. A padded store writes it into an unused slot. */
    fun createWallet(): String = synchronized(this) {
        val key = requireOpen()
        writes++
        owned = null
        val unused = pad?.take()
        val id = unused ?: UUID.randomUUID().toString()
        val dir = layout.walletDir(id)
        if (unused != null) pad.clearForWallet(id) else dir.mkdirs()
        val walletKey = ByteArray(WALLET_KEY_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            WalletFolder.writeKeyFile(dir, keys, key, id, walletKey)
        } finally {
            Arrays.fill(walletKey, 0)
        }
        id
    }

    /**
     * Stores [bytes] as record [name] of wallet [walletId], replacing any earlier value. The name must be one a wallet folder holds, see
     * [WalletFolder].
     *
     * @throws IllegalArgumentException if the id is malformed, the name is not a wallet record, the record is longer than its capacity,
     * or the wallet does not exist.
     */
    fun putRecord(walletId: String, name: String, bytes: ByteArray): Unit = synchronized(this) {
        val key = requireOpen()
        writes++
        requireWalletId(walletId)
        requireWalletRecord(name)
        val walletKey = unwrapWalletKey(key, walletId)
            ?: throw IllegalArgumentException("No wallet with id $walletId.")
        try {
            writeAtomically(layout.walletRecordFile(walletId, name), WalletFolder.seal(walletKey, walletId, name, bytes))
        } finally {
            Arrays.fill(walletKey, 0)
        }
    }

    /** The record's bytes, or null if the wallet or the record does not exist (including after [deleteWallet]). */
    fun getRecord(walletId: String, name: String): ByteArray? {
        synchronized(this) {
            val key = requireOpen()
            requireWalletId(walletId)
            // A name that no wallet folder holds has no record. A file that holds no record of this wallet reads as absent.
            if (!WalletFolder.holds(name)) return null
            val file = layout.walletRecordFile(walletId, name)
            if (!file.isFile) return null
            val walletKey = unwrapWalletKey(key, walletId) ?: return null
            try {
                return WalletFolder.open(walletKey, walletId, name, file.readBytes())
            } finally {
                Arrays.fill(walletKey, 0)
            }
        }
    }

    /**
     * True when wallet [walletId] has a file for record [name], whether or not the record reads. [getRecord] gives null both for an absent
     * record and for one that cannot be read, so a caller that must tell them apart asks here.
     */
    fun hasRecord(walletId: String, name: String): Boolean = synchronized(this) {
        requireOpen()
        requireWalletId(walletId)
        WalletFolder.holds(name) && layout.walletRecordFile(walletId, name).isFile
    }

    /**
     * The bytes of each record in [names] of wallet [walletId] that exists, by name. A record that is absent, or a name no wallet folder
     * holds, is left out, as [getRecord] gives null for it. The Per-Wallet Key is unwrapped once for all of them, so reading many records
     * costs one Keystore call, not one per record.
     */
    fun getRecords(walletId: String, names: Collection<String>): Map<String, ByteArray> {
        synchronized(this) {
            val key = requireOpen()
            requireWalletId(walletId)
            val files = names.filter { WalletFolder.holds(it) }.distinct()
                .associateWith { name -> layout.walletRecordFile(walletId, name) }
                .filterValues { it.isFile }
            if (files.isEmpty()) return emptyMap()
            val walletKey = unwrapWalletKey(key, walletId) ?: return emptyMap()
            try {
                return files.mapNotNull { (name, file) ->
                    WalletFolder.open(walletKey, walletId, name, file.readBytes())?.let { name to it }
                }.toMap()
            } finally {
                Arrays.fill(walletKey, 0)
            }
        }
    }

    /**
     * Erases record [name] of wallet [walletId]: its file is overwritten with random bytes, so the record no longer reads and the folder
     * keeps its shape. Does nothing if the record does not exist, or if the wallet is not this session's.
     */
    fun deleteRecord(walletId: String, name: String): Unit = synchronized(this) {
        val key = requireOpen()
        writes++
        requireWalletId(walletId)
        requireWalletRecord(name)
        // The record's file is overwritten, not removed, so the folder keeps its shape. Another vault's wallet is not this session's.
        if (ownsWallet(keys, layout, key, walletId)) WalletFolder.erase(layout.walletDir(walletId), name)
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
        writes++
        requireWalletId(walletId)
        (puts.keys + deletes).forEach { requireWalletRecord(it) }
        require(puts.keys.none { it in deletes }) { "A record cannot be stored and removed in one write." }
        val walletKey = unwrapWalletKey(key, walletId)
            ?: throw IllegalArgumentException("No wallet with id $walletId.")
        // Every record is sealed before any file changes, so a failure here writes nothing.
        val sealed = try {
            puts.map { (name, bytes) -> layout.walletRecordFile(walletId, name) to WalletFolder.seal(walletKey, walletId, name, bytes) }
        } finally {
            Arrays.fill(walletKey, 0)
        }
        val targets = (sealed.map { it.first } + deletes.map { layout.walletRecordFile(walletId, it) }).distinct()
        // What each target holds now, so a failed write can put it back. Null means the record does not exist.
        val before = targets.associateWith { file -> if (file.isFile) file.readBytes() else null }
        try {
            sealed.forEach { (file, blob) -> writeAtomically(file, blob) }
            deletes.forEach { name -> WalletFolder.erase(layout.walletDir(walletId), name) }
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
        writes++
        requireWalletId(walletId)
        requireReviewName(name)
        val walletKey = unwrapWalletKey(key, walletId)
            ?: throw IllegalArgumentException("No wallet with id $walletId.")
        try {
            ReviewPool(layout.reviewPool, key).use { it.put(walletId, walletKey, name, bytes) }
        } finally {
            Arrays.fill(walletKey, 0)
        }
    }

    /** The Review Record [name] of wallet [walletId], or null if there is none. The caller zeroes the array. */
    fun getReview(walletId: String, name: String): ByteArray? = synchronized(this) {
        val key = requireOpen()
        requireWalletId(walletId)
        requireReviewName(name)
        ReviewPool(layout.reviewPool, key).use { pool ->
            // A record with no slot is absent. Checking that first means a deleted wallet's key file is never needed to say so.
            if (pool.fileOf(walletId, name) == null) return@synchronized null
            val walletKey = unwrapWalletKey(key, walletId) ?: return@synchronized null
            try {
                pool.get(walletId, walletKey, name)
            } finally {
                Arrays.fill(walletKey, 0)
            }
        }
    }

    /**
     * The Review Records of wallet [walletId] with their names, sorted by name, read in one pass over the pool. A record that cannot
     * be opened is left out. The caller zeroes the arrays. A wallet this session cannot open has none.
     */
    fun reviewRecords(walletId: String): List<Pair<String, ByteArray>> = synchronized(this) {
        val key = requireOpen()
        requireWalletId(walletId)
        if (!ownsWallet(keys, layout, key, walletId)) return@synchronized emptyList()
        ReviewPool(layout.reviewPool, key).use { pool ->
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
    }

    /** The names of the Review Records of wallet [walletId], sorted. A wallet this session cannot open has none. */
    fun reviewNames(walletId: String): List<String> = synchronized(this) {
        val key = requireOpen()
        requireWalletId(walletId)
        if (!ownsWallet(keys, layout, key, walletId)) return@synchronized emptyList()
        ReviewPool(layout.reviewPool, key).use { it.names(walletId) }
    }

    /**
     * Crypto-shreds a wallet: its folder's Keystore key goes first, so its key file and records cannot be read even if a copy
     * survives. Then the folder's files and the wallet's Review Records are overwritten, and the folder becomes an unused slot. Does
     * nothing if the wallet does not exist, or if this session cannot open it. A wallet of the other vault is never deleted from here.
     *
     * @throws VaultException if the Keystore could not delete the wallet's key. The wallet is removed from the list all the same, and the
     * next unlock deletes the key.
     */
    fun deleteWallet(walletId: String) {
        synchronized(this) {
            val key = requireOpen()
            writes++
            owned = null
            requireWalletId(walletId)
            val dir = layout.walletDir(walletId)
            if (!dir.isDirectory) return
            if (!ownsWallet(keys, layout, key, walletId)) return
            val erased = if (pad != null) {
                pad.release(listOf(walletId))
            } else {
                WalletFolder.retireKey(dir, keys).also { dir.deleteRecursively() }
            }
            ReviewPool(layout.reviewPool, key).use { it.release(walletId) }
            if (!erased) throw VaultException("The wallet was removed, but its Keystore key could not be deleted.")
        }
    }

    /**
     * Stores [bytes] as the vault record [name], replacing any earlier value. A vault record belongs to the vault, not to a
     * wallet, so it holds no wallet data: a setting, for example. It is encrypted under the Vault Key itself, with its own
     * AAD, and its file name is keyed by the Vault Key (see [VaultRecordNames]). [Vault.rekey] keeps the Vault Key, so the
     * record stays readable after a password change, and [Vault.wipe] removes it.
     *
     * @throws IllegalArgumentException if the name is not in [VaultRecordNames.KNOWN].
     */
    fun putVaultRecord(name: String, bytes: ByteArray) {
        synchronized(this) {
            val key = requireOpen()
            requireRecordName(name)
            layout.vaultRecords.mkdirs()
            val file = vaultRecordFile(key, name)
            VaultRecordNames.underFolderLock {
                // The file keeps the size it has, so a written record stays in the size class of its placeholder or spare.
                val floor = if (file.isFile) file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else VaultRecordNames.fileBytesOf(name)
                writeAtomically(file, VaultRecordNames.sealed(key, name, bytes, floor))
            }
        }
        // The pass copies sealed bytes and needs no key, so it runs outside the session lock and reads of other records are not held up.
        VaultRecordNames.rewriteAll(layout.vaultRecords)
    }

    /** The vault record [name], or null if it has never been stored. */
    fun getVaultRecord(name: String): ByteArray? = synchronized(this) {
        val key = requireOpen()
        requireRecordName(name)
        val file = vaultRecordFile(key, name)
        if (!file.isFile) return@synchronized null
        val plain = try {
            Aead.open(key, VaultRecordNames.aad(name), decodeBlob(file.readBytes()))
        } catch (e: AEADBadTagException) {
            throw VaultException("Vault record '$name' failed authentication.", e)
        }
        try {
            VaultRecordNames.valueOf(plain)
        } finally {
            Arrays.fill(plain, 0)
        }
    }

    /** Creates the pool's first batch if this vault has none, so an empty vault still shows a full pool. */
    internal fun ensureReviewPool(): Unit = synchronized(this) {
        ReviewPool(layout.reviewPool, requireOpen()).use { it.ensureFirstBatch() }
    }

    /**
     * Brings the wallet folders to the layout: the store is padded to its size, unused slots take the folder shape, the Keystore keys are
     * reconciled with the folders, and each wallet this session owns is settled. Called on unlock.
     */
    internal fun ensurePadded(): Unit = synchronized(this) {
        val key = requireOpen()
        pad?.ensure()
        WalletFolder.reconcileFolderKeys(layout.wallets, keys)
        for (walletId in listWallets()) {
            val walletKey = unwrapWalletKey(key, walletId) ?: continue
            try {
                WalletFolder.settle(layout.walletDir(walletId), walletId, walletKey)
            } finally {
                Arrays.fill(walletKey, 0)
            }
        }
    }

    /**
     * Makes this vault's photo key pair if it has none, and writes the public half to [VaultLayout.photoKeyFile] when that file differs.
     * The private half is a vault record, so only this vault's password opens it. The other side's file is made too, with a placeholder
     * until a Decoy Vault of that side has its own key. Called on unlock, and when a vault is created.
     */
    internal fun ensurePhotoKey(): Unit = synchronized(this) {
        layout.makePhotoFolders()
        val publicKey = ensurePhotoKeyRecord()
        val file = layout.photoKeyFile(side)
        if (!file.isFile || !file.readBytes().contentEquals(publicKey)) writeAtomically(file, publicKey)
        val otherFile = layout.photoKeyFile(1 - side)
        if (!otherFile.isFile) writeAtomically(otherFile, placeholderPhotoKey())
    }

    /** Makes this vault's photo key pair if it has none, and returns the public half. Writes no key file, so a caller can choose when. */
    internal fun ensurePhotoKeyRecord(): ByteArray = synchronized(this) {
        val privateKey = photoPrivateKey()
            ?: MediaEnvelope.newPrivateKey().also { putVaultRecord(VaultRecordNames.PHOTO_KEY, it) }
        try {
            MediaEnvelope.publicKeyOf(privateKey)
        } finally {
            privateKey.fill(0)
        }
    }

    /**
     * The private half of this vault's photo key, or null when it has none. The caller zeroes it. The record holds the key, and while the
     * photo copies of a Duress Password are not all made (see [photoCopiesPending]) the decoy's public key after it.
     */
    fun photoPrivateKey(): ByteArray? = synchronized(this) {
        val record = getVaultRecord(VaultRecordNames.PHOTO_KEY) ?: return@synchronized null
        try {
            record.copyOfRange(0, minOf(record.size, MediaEnvelope.KEY_BYTES))
        } finally {
            record.fill(0)
        }
    }

    /**
     * The public photo key of the decoy that the copies of the cover's photos are still being made for, or null when no copy is pending. It
     * sits in the photo key record, behind the private half, so the record file keeps its name and size and no other file shows it, and only
     * this vault's key opens it. [Vault] sets it before it queues the copies and clears it when the last one is made.
     */
    internal fun photoCopiesPending(): ByteArray? = synchronized(this) {
        val record = getVaultRecord(VaultRecordNames.PHOTO_KEY) ?: return@synchronized null
        try {
            if (record.size == 2 * MediaEnvelope.KEY_BYTES) record.copyOfRange(MediaEnvelope.KEY_BYTES, record.size) else null
        } finally {
            record.fill(0)
        }
    }

    /**
     * Marks the copies for the decoy with public key [decoyPublicKey] as pending, or clears the mark when it is null. It changes an existing
     * photo key record only, so it never adds a file.
     *
     * @throws VaultException when the vault has no photo key record.
     */
    internal fun setPhotoCopiesPending(decoyPublicKey: ByteArray?): Unit = synchronized(this) {
        val record = getVaultRecord(VaultRecordNames.PHOTO_KEY) ?: throw VaultException("The vault has no photo key.")
        val next = record.copyOfRange(0, minOf(record.size, MediaEnvelope.KEY_BYTES)) + (decoyPublicKey ?: ByteArray(0))
        try {
            if (!next.contentEquals(record)) putVaultRecord(VaultRecordNames.PHOTO_KEY, next)
        } finally {
            record.fill(0)
            next.fill(0)
        }
    }

    /** Clears the mark of [photoCopiesPending] when it is the one for [decoyPublicKey]: a later Duress Password has its own mark. */
    internal fun clearPhotoCopiesPending(decoyPublicKey: ByteArray): Unit = synchronized(this) {
        val pending = photoCopiesPending() ?: return@synchronized
        try {
            if (pending.contentEquals(decoyPublicKey)) setPhotoCopiesPending(null)
        } finally {
            pending.fill(0)
        }
    }

    /**
     * Makes the pending photo key pair (see [PendingPhotoKey]) this vault's photo key, when photos were taken before the first setup, and
     * removes its pending copy. Called once, when the vault is created, before [ensurePhotoKey], which then keeps the pair. The pair is
     * stored before its copy is removed. If it cannot be stored, the pair and its photos are erased, since nothing can open them.
     */
    internal fun adoptPendingPhotoKey(): Unit = synchronized(this) {
        val privateKey = PendingPhotoKey.read(layout, keys) ?: return@synchronized
        val stored = try {
            putVaultRecord(VaultRecordNames.PHOTO_KEY, privateKey)
            true
        } catch (e: IOException) {
            false
        } catch (e: GeneralSecurityException) {
            false
        } finally {
            privateKey.fill(0)
        }
        if (stored) PendingPhotoKey.discard(layout, keys) else PendingPhotoKey.erase(layout, keys)
    }

    /** Zeroes the Vault Key and the pad key. Any later call on this session throws [IllegalStateException]. */
    fun close() {
        synchronized(this) {
            vaultKey?.let { Arrays.fill(it, 0) }
            vaultKey = null
            owned = null
            pad?.close()
        }
    }

    /** A copy of the Vault Key, for [Vault.rekey], the Duress Password operations and [BiometricUnlock]. The caller zeroes it. */
    internal fun copyOfVaultKey(): ByteArray = synchronized(this) { requireOpen().copyOf() }

    /** Runs [block] with the Vault Key while no other call on this session runs. The key is the session's own, and [block] must not keep it. */
    internal fun <T> withKeyLocked(block: (ByteArray) -> T): T = synchronized(this) { block(requireOpen()) }

    /**
     * A key for [purpose], derived from the Vault Key with HKDF-SHA256 (RFC 5869), with [purpose] as the info. Each purpose gives its own key,
     * and the key does not reveal the Vault Key. The caller zeroes the result.
     */
    fun deriveKey(purpose: String): ByteArray = synchronized(this) {
        hkdfSha256(requireOpen(), salt = null, info = purpose.toByteArray(StandardCharsets.UTF_8), length = DERIVED_KEY_BYTES)
    }

    /** The file that holds vault record [name] for this session. For tests only. */
    fun vaultRecordFileForTesting(name: String): File = synchronized(this) {
        vaultRecordFile(requireOpen(), name)
    }

    /** The pool file that holds Review Record [name] of wallet [walletId]. For tests only. */
    fun reviewFileForTesting(walletId: String, name: String): File? = synchronized(this) {
        ReviewPool(layout.reviewPool, requireOpen()).use { it.fileOf(walletId, name) }
    }

    private fun requireOpen(): ByteArray = vaultKey ?: throw IllegalStateException("The VaultSession is closed.")

    /**
     * Checks every wallet folder once and returns the ids this session owns, sorted. A folder the Keystore cannot check is left out, and
     * then nothing is kept, so the next [listWallets] checks again.
     */
    private fun scanOwned(key: ByteArray): List<String> {
        val found = mutableListOf<String>()
        var complete = true
        for (id in layout.wallets.list().orEmpty()) {
            try {
                if (ownsWallet(keys, layout, key, id)) found += id
            } catch (e: VaultException) {
                complete = false
            }
        }
        val sorted = found.sorted()
        if (complete) owned = sorted
        return sorted
    }

    private fun vaultRecordFile(vaultKey: ByteArray, name: String): File =
        File(layout.vaultRecords, VaultRecordNames.fileName(vaultKey, name))

    /**
     * Returns the Per-Wallet Key, or null if the wallet has no key file. A key file this vault does not own is an error. The caller zeroes
     * the result.
     */
    private fun unwrapWalletKey(vaultKey: ByteArray, walletId: String): ByteArray? {
        if (!layout.walletKeyFile(walletId).isFile) return null
        return WalletFolder.walletKeyOf(keys, layout.walletDir(walletId), vaultKey, walletId)
            ?: throw VaultException("The key of wallet $walletId failed authentication.")
    }

    private companion object {
        val REVIEW_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        fun requireWalletId(id: String) {
            require(WALLET_ID.matches(id)) { "Malformed wallet id." }
        }

        /** A wallet record must be one that a wallet folder holds, so no other name can add a file to the folder. */
        fun requireWalletRecord(name: String) {
            require(WalletFolder.holds(name)) { "Invalid record name." }
        }

        /** A Review Record name. */
        fun requireReviewName(name: String) {
            require(REVIEW_NAME.matches(name)) { "Invalid record name." }
        }

        /** A vault record name must be one of [VaultRecordNames.KNOWN], or a wipe and a Decoy Vault removal would mishandle its file. */
        fun requireRecordName(name: String) {
            require(name in VaultRecordNames.KNOWN) { "Unknown record name: add it to VaultRecordNames.KNOWN." }
        }
    }
}

internal fun encodeBlob(sealed: ByteArray): ByteArray = byteArrayOf(BLOB_VERSION) + sealed

internal fun decodeBlob(bytes: ByteArray): ByteArray {
    if (bytes.size < 1 + Aead.IV_BYTES + 16 || bytes[0] != BLOB_VERSION) {
        throw VaultException("A wallet file is damaged or not in a format this application reads.")
    }
    return bytes.copyOfRange(1, bytes.size)
}
