package app.lensly.core.vault

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Sizes of the Review Record pool. Every pool file has the same size, so the pool's shape shows neither record count nor length. */
internal object ReviewPoolFormat {
    /** Files added per batch. A batch is one index page and [SLOTS] record slots. */
    const val BATCH = 64

    /** Record slots in a batch. Its first file is the index page. */
    const val SLOTS = BATCH - 1

    /**
     * Bytes of payload in every pool file, a record's length prefix included. A signed message is at most 64 KiB, so a record
     * of one fits with its other fields.
     */
    const val PAYLOAD_BYTES = 72 * 1024

    /** The largest record one slot holds. */
    const val RECORD_BYTES = PAYLOAD_BYTES - 4

    /** Size of every pool file: a version byte, the sealed payload (IV, payload, tag). */
    const val FILE_BYTES = 1 + Aead.IV_BYTES + PAYLOAD_BYTES + 16

    const val NAME_BYTES = 64
    const val WALLET_ID_BYTES = 36

    /** One index entry: a used flag, the wallet id, and the record name, each fixed width. */
    const val ENTRY_BYTES = 1 + WALLET_ID_BYTES + NAME_BYTES

    const val VERSION: Byte = 1
}

/**
 * The shared pool of Review Records. It lives in one folder outside every wallet folder, so a reader of the storage cannot tell
 * which wallet a record belongs to, or how many records exist.
 *
 * The pool grows in batches of [ReviewPoolFormat.BATCH] files. A batch holds one index page and [ReviewPoolFormat.SLOTS] record
 * slots. Every name is derived from the vault key, so the names look random and only this vault can find its own files. The
 * index page lists which wallet and record each slot holds, and it is sealed under a key derived from this vault's key. A
 * Decoy Vault has its own key, so it can neither find nor read this vault's pages or slots.
 *
 * A record is sealed under its wallet's Per-Wallet Key. A free slot holds random bytes of the same size. Deleting a record, or a
 * wallet, overwrites its slots in place with random bytes and syncs them.
 *
 * Methods take the wallet's key from the caller, and the caller zeroes it. The pool itself holds only the vault key.
 */
internal class ReviewPool(private val dir: File, private val vaultKey: ByteArray) {
    private val random = SecureRandom()
    private val indexKey: ByteArray = labelledHmac(vaultKey, "lensly.pool.index")

    /** What one index entry says a slot holds. */
    internal data class Entry(val walletId: String, val name: String)

    /** The number of complete batches. A batch is complete once its index page exists, since the page is written last. */
    fun batches(): Int {
        var count = 0
        while (pageFile(count).isFile) count++
        return count
    }

    /** Creates the first batch if the pool has none. A new vault calls this, so its pool never starts empty. */
    fun ensureFirstBatch() {
        if (batches() == 0) createBatch(0)
    }

    /**
     * Stores [bytes] as record [name] of wallet [walletId], sealed under [walletKey]. A record already stored under the name is
     * replaced in its own slot. Otherwise the record takes a free slot, and a new batch is added when none is free.
     *
     * @throws IllegalArgumentException if the record is larger than one slot.
     */
    fun put(walletId: String, walletKey: ByteArray, name: String, bytes: ByteArray) {
        require(bytes.size <= ReviewPoolFormat.RECORD_BYTES) { "The Review Record is too large for one pool slot." }
        require(walletId.length == ReviewPoolFormat.WALLET_ID_BYTES && name.length <= ReviewPoolFormat.NAME_BYTES) { "Malformed entry." }
        val existing = locate(walletId, name)
        if (existing != null) {
            writeAtomically(slotFile(existing.first, existing.second + 1), sealSlot(walletKey, walletId, name, bytes))
            return
        }
        var batch = -1
        var entries: MutableList<Entry?> = mutableListOf()
        for (candidate in 0 until batches()) {
            val page = readPage(candidate)
            if (page.any { it == null }) {
                batch = candidate
                entries = page.toMutableList()
                break
            }
        }
        if (batch < 0) {
            batch = batches()
            createBatch(batch)
            entries = MutableList(ReviewPoolFormat.SLOTS) { null }
        }
        val index = entries.indexOfFirst { it == null }
        // The entry is marked used before the record is written, so a crash cannot leave a free slot that holds a record.
        entries[index] = Entry(walletId, name)
        writePage(batch, entries)
        writeAtomically(slotFile(batch, index + 1), sealSlot(walletKey, walletId, name, bytes))
    }

    /** The record [name] of wallet [walletId], or null if the pool holds no such record. The caller zeroes the bytes. */
    fun get(walletId: String, walletKey: ByteArray, name: String): ByteArray? {
        val (batch, index) = locate(walletId, name) ?: return null
        val file = slotFile(batch, index + 1)
        if (!file.isFile) return null
        return openSlot(walletKey, walletId, name, file.readBytes())
    }

    /** The names of the records of wallet [walletId], sorted. */
    fun names(walletId: String): List<String> = (0 until batches())
        .flatMap { batch -> readPage(batch).filterNotNull().filter { it.walletId == walletId }.map { it.name } }
        .sorted()

    /** Overwrites every slot of wallet [walletId] with random bytes, syncs them, and frees their entries. */
    fun release(walletId: String) {
        for (batch in 0 until batches()) {
            val entries = readPage(batch).toMutableList()
            var changed = false
            entries.forEachIndexed { index, entry ->
                if (entry != null && entry.walletId == walletId) {
                    overwriteInPlace(slotFile(batch, index + 1))
                    entries[index] = null
                    changed = true
                }
            }
            if (changed) writePage(batch, entries)
        }
    }

    /** The slot of every record of wallet [walletId], by record name, from one pass over the index pages. */
    fun slotsOf(walletId: String): Map<String, File> {
        val slots = linkedMapOf<String, File>()
        for (batch in 0 until batches()) {
            readPage(batch).forEachIndexed { index, entry ->
                if (entry != null && entry.walletId == walletId) slots[entry.name] = slotFile(batch, index + 1)
            }
        }
        return slots
    }

    /** The record in [file], a slot of wallet [walletId] named [name], opened with [walletKey]. The caller zeroes the bytes. */
    internal fun readSlot(walletKey: ByteArray, walletId: String, name: String, file: File): ByteArray =
        openSlot(walletKey, walletId, name, file.readBytes())

    /** The file names this vault's batches occupy. A file under any other name belongs to no batch of this vault. */
    fun ownedNames(): Set<String> = (0 until batches())
        .flatMap { batch -> (0..ReviewPoolFormat.SLOTS).map { slotFile(batch, it).name } }
        .toSet()

    /** The file that holds record [name] of wallet [walletId], or null if there is none. For tests and migration checks. */
    internal fun fileOf(walletId: String, name: String): File? {
        val (batch, index) = locate(walletId, name) ?: return null
        return slotFile(batch, index + 1)
    }

    /** The index page of [batch], read with this vault's key. */
    internal fun readPage(batch: Int): List<Entry?> = decodePage(pageFile(batch), batch)

    /** Decodes the index page in [file] as page [batch] under this vault's key. Another vault's page throws [VaultException]. */
    internal fun decodePage(file: File, batch: Int): List<Entry?> {
        val plain = openFile(indexKey, pageAad(batch), file.readBytes())
        try {
            return List(ReviewPoolFormat.SLOTS) { index ->
                val at = index * ReviewPoolFormat.ENTRY_BYTES
                if (plain[at].toInt() == 0) {
                    null
                } else {
                    Entry(
                        text(plain, at + 1, ReviewPoolFormat.WALLET_ID_BYTES),
                        text(plain, at + 1 + ReviewPoolFormat.WALLET_ID_BYTES, ReviewPoolFormat.NAME_BYTES),
                    )
                }
            }
        } finally {
            plain.fill(0)
        }
    }

    private fun locate(walletId: String, name: String): Pair<Int, Int>? {
        for (batch in 0 until batches()) {
            val index = readPage(batch).indexOfFirst { it != null && it.walletId == walletId && it.name == name }
            if (index >= 0) return batch to index
        }
        return null
    }

    /** Creates the files of [batch]: random slots first, then the index page, so a batch without its page is not a batch. */
    private fun createBatch(batch: Int) {
        dir.mkdirs()
        for (slot in 1..ReviewPoolFormat.SLOTS) {
            writeAtomically(slotFile(batch, slot), randomBytes())
        }
        writePage(batch, List(ReviewPoolFormat.SLOTS) { null })
    }

    private fun writePage(batch: Int, entries: List<Entry?>) {
        val plain = ByteArray(ReviewPoolFormat.PAYLOAD_BYTES)
        try {
            entries.forEachIndexed { index, entry ->
                if (entry != null) {
                    val at = index * ReviewPoolFormat.ENTRY_BYTES
                    plain[at] = 1
                    entry.walletId.toByteArray(StandardCharsets.US_ASCII).copyInto(plain, at + 1)
                    entry.name.toByteArray(StandardCharsets.US_ASCII).copyInto(plain, at + 1 + ReviewPoolFormat.WALLET_ID_BYTES)
                }
            }
            writeAtomically(pageFile(batch), sealFile(indexKey, pageAad(batch), plain))
        } finally {
            plain.fill(0)
        }
    }

    private fun sealSlot(walletKey: ByteArray, walletId: String, name: String, bytes: ByteArray): ByteArray {
        val plain = ByteArray(ReviewPoolFormat.PAYLOAD_BYTES)
        try {
            ByteBuffer.wrap(plain, 0, 4).putInt(bytes.size)
            bytes.copyInto(plain, 4)
            return sealFile(walletKey, slotAad(walletId, name), plain)
        } finally {
            plain.fill(0)
        }
    }

    private fun openSlot(walletKey: ByteArray, walletId: String, name: String, bytes: ByteArray): ByteArray {
        val plain = openFile(walletKey, slotAad(walletId, name), bytes)
        try {
            val size = ByteBuffer.wrap(plain, 0, 4).int
            if (size < 0 || size > ReviewPoolFormat.RECORD_BYTES) throw VaultException("A Review Record pool slot is damaged.")
            return plain.copyOfRange(4, 4 + size)
        } finally {
            plain.fill(0)
        }
    }

    private fun sealFile(key: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        byteArrayOf(ReviewPoolFormat.VERSION) + Aead.seal(key, aad, plain)

    /** The plain payload of a pool file. Its size is fixed, so a file of another size is damaged. */
    private fun openFile(key: ByteArray, aad: ByteArray, bytes: ByteArray): ByteArray {
        if (bytes.size != ReviewPoolFormat.FILE_BYTES || bytes[0] != ReviewPoolFormat.VERSION) {
            throw VaultException("A Review Record pool file is damaged or not in a format this app reads.")
        }
        return try {
            Aead.open(key, aad, bytes.copyOfRange(1, bytes.size))
        } catch (e: AEADBadTagException) {
            throw VaultException("A Review Record pool file failed authentication.", e)
        }
    }

    private fun text(bytes: ByteArray, at: Int, length: Int): String =
        String(bytes, at, length, StandardCharsets.US_ASCII).trimEnd('\u0000')

    private fun randomBytes(): ByteArray = ByteArray(ReviewPoolFormat.FILE_BYTES).also { random.nextBytes(it) }

    internal fun pageFile(batch: Int): File = slotFile(batch, 0)

    /** The file for [slot] of [batch], named by a keyed hash so the name reveals neither its vault nor its place. */
    private fun slotFile(batch: Int, slot: Int): File {
        val digest = labelledHmac(vaultKey, "lensly.pool.file\u0000$batch\u0000$slot")
        return File(dir, digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) } + ".bin")
    }

    private fun pageAad(batch: Int): ByteArray = "lensly.pool.page\u0000$batch".toByteArray(StandardCharsets.US_ASCII)

    private fun slotAad(walletId: String, name: String): ByteArray =
        "lensly.pool.slot\u0000$walletId\u0000$name".toByteArray(StandardCharsets.UTF_8)

    companion object {
        /** Overwrites every file in [dir] whose name is not in [keep] with random bytes of its own size, and syncs it. */
        fun shredExcept(dir: File, keep: Set<String>) {
            dir.listFiles()?.filter { it.isFile && it.name !in keep }?.forEach { overwriteInPlace(it) }
        }

        /** Overwrites every file in [dir] with random bytes and syncs it. The files stay, so the pool keeps its shape. */
        fun shredAll(dir: File) = shredExcept(dir, emptySet())
    }
}

/**
 * Overwrites [file] with random bytes of its own length, in place, and syncs it. A file that is only renamed or deleted keeps
 * its old bytes on disk until the storage reuses them, so the bytes are replaced first.
 */
internal fun overwriteInPlace(file: File) {
    if (!file.isFile) return
    val random = SecureRandom()
    RandomAccessFile(file, "rw").use { raf ->
        val bytes = ByteArray(raf.length().toInt()).also { random.nextBytes(it) }
        raf.seek(0)
        raf.write(bytes)
        raf.fd.sync()
        bytes.fill(0)
    }
}

/** HMAC-SHA256 of [label] under [key]. Each vault derives its own subkeys this way, so no subkey reveals another vault's. */
internal fun labelledHmac(key: ByteArray, label: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
    return mac.doFinal(label.toByteArray(StandardCharsets.UTF_8))
}
