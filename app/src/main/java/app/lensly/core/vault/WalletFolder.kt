package app.lensly.core.vault

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Arrays

/**
 * The files of a wallet folder, and how a record is sealed in one.
 *
 * Every wallet folder holds the same files with the same sizes, whether it belongs to the real vault, to the Decoy Vault, or is an
 * unused slot: `pwk.bin`, then `<name>.bin` for each record name in [NAMES]. A record that a wallet does not have is random bytes of its
 * file's size, and a record that it has is sealed to that same size. So the folder shape says nothing about the wallet inside.
 *
 * A sealed record holds the record's length, the record, and random filler up to the record's capacity. Random bytes never authenticate,
 * so [open] returns null for an absent record, and a random file is never read as a wallet's.
 */
internal object WalletFolder {
    /** The record of the wallet's kind, name and creation time. */
    const val META = "meta"

    /** The record of the wallet's secret: its recovery words and passphrase, or its private key. */
    const val SECRET = "secret"

    /** The record that holds the wallet's name at creation. */
    const val BASE_NAME = "base-name"

    /** The record of the wallet's profile: its colour, icon, label and groups. */
    const val PROFILE = "profile"

    /**
     * The account records a wallet can hold: accounts 0 to ACCOUNT_SLOTS - 1. Every folder holds all of them, so the count is fixed.
     * It is the largest Bulk Cap, so the first 100 accounts of a wallet can each be named and styled.
     */
    const val ACCOUNT_SLOTS = 100

    /** The size of a wallet key, which the key file wraps. */
    private const val KEY_BYTES = 32

    private const val LENGTH_BYTES = 2
    private const val TAG_BYTES = 16
    private const val ACCOUNT_PREFIX = "account-"
    private const val ACCOUNT_CAPACITY = 256

    /** The plaintext each fixed record can hold. A record that does not fit is refused. */
    private val CAPACITY: Map<String, Int> = mapOf(META to 256, SECRET to 2048, BASE_NAME to 128, PROFILE to 512)

    /** The size of `pwk.bin`, a real wallet's key file and an unused slot's alike. */
    internal const val KEY_FILE_BYTES = 1 + Aead.IV_BYTES + KEY_BYTES + TAG_BYTES

    private val random = SecureRandom()

    /** The record names a wallet folder holds, in file order: the fixed records, then the account records. */
    val NAMES: List<String> = listOf(META, SECRET, BASE_NAME, PROFILE) + (0 until ACCOUNT_SLOTS).map { accountName(it) }

    /** The name of the record of account [index]. */
    fun accountName(index: Int): String = ACCOUNT_PREFIX + index

    /** True when a wallet folder holds a record named [name]. Any other name is not a wallet record. */
    fun holds(name: String): Boolean = capacityOf(name) != null

    private fun capacityOf(name: String): Int? =
        CAPACITY[name] ?: if (accountIndexOf(name) in 0 until ACCOUNT_SLOTS) ACCOUNT_CAPACITY else null

    /** The index of the account that record [name] belongs to, or -1 when [name] is not an account record name. */
    private fun accountIndexOf(name: String): Int {
        if (!name.startsWith(ACCOUNT_PREFIX)) return -1
        val digits = name.substring(ACCOUNT_PREFIX.length)
        val index = digits.toIntOrNull() ?: return -1
        return if (index >= 0 && index.toString() == digits) index else -1
    }

    /** The size of the file of record [name]: the version byte, the IV, the length, the padded record, and the tag. */
    private fun sizeOf(name: String): Int {
        val capacity = capacityOf(name)
        require(capacity != null) { "Invalid record name." }
        return 1 + Aead.IV_BYTES + LENGTH_BYTES + capacity + TAG_BYTES
    }

    /**
     * Seals [bytes] as record [name] of wallet [walletId], under the wallet's [walletKey]. The record is padded to its capacity, so every
     * file of that name has the same size.
     *
     * @throws IllegalArgumentException if [name] is not a wallet record, or [bytes] is longer than its capacity.
     */
    fun seal(walletKey: ByteArray, walletId: String, name: String, bytes: ByteArray): ByteArray {
        val capacity = capacityOf(name)
        require(capacity != null) { "Invalid record name." }
        require(bytes.size <= capacity) { "A record of this kind holds at most $capacity bytes." }
        val plain = ByteArray(LENGTH_BYTES + capacity)
        try {
            random.nextBytes(plain)
            plain[0] = (bytes.size ushr 8).toByte()
            plain[1] = bytes.size.toByte()
            bytes.copyInto(plain, LENGTH_BYTES)
            return encodeBlob(Aead.seal(walletKey, paddedAad(walletId, name), plain))
        } finally {
            Arrays.fill(plain, 0)
        }
    }

    /**
     * The bytes of record [name] in [file], or null when the file holds no record of this wallet. An absent record is random bytes, which
     * do not authenticate, so it reads as null. The caller zeroes the array.
     */
    fun open(walletKey: ByteArray, walletId: String, name: String, file: ByteArray): ByteArray? {
        val capacity = capacityOf(name) ?: return null
        val plain = try {
            Aead.open(walletKey, paddedAad(walletId, name), decodeBlob(file))
        } catch (e: GeneralSecurityException) {
            return null
        } catch (e: VaultException) {
            return null
        }
        try {
            val length = ((plain[0].toInt() and 0xff) shl 8) or (plain[1].toInt() and 0xff)
            if (length > capacity) return null
            return plain.copyOfRange(LENGTH_BYTES, LENGTH_BYTES + length)
        } finally {
            Arrays.fill(plain, 0)
        }
    }

    /**
     * The bytes of a record that an earlier build sealed as [walletId] and [name] with no padding, or null when [file] is not one. The
     * caller zeroes the array.
     */
    fun legacyOpen(walletKey: ByteArray, walletId: String, name: String, file: ByteArray): ByteArray? = try {
        Aead.open(walletKey, legacyAad(walletId, name), decodeBlob(file))
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: VaultException) {
        null
    }

    /** Overwrites record [name] of folder [dir] with random bytes of its size, if the folder holds that record. */
    fun erase(dir: File, name: String) {
        val file = File(dir, "$name.bin")
        if (file.isFile) writeRandom(file, sizeOf(name))
    }

    /** Makes [dir] an unused slot: exactly the files of a wallet folder, each random bytes of its size. Other files are removed. */
    fun makeUnused(dir: File) {
        dir.mkdirs()
        val wanted = NAMES.map { "$it.bin" }.toSet() + WALLET_KEY_FILE_NAME
        dir.listFiles().orEmpty().forEach { child ->
            if (!child.isFile || child.name !in wanted) {
                if (child.isFile) overwriteInPlace(child)
                child.deleteRecursively()
            }
        }
        writeRandom(File(dir, WALLET_KEY_FILE_NAME), KEY_FILE_BYTES)
        NAMES.forEach { name -> writeRandom(File(dir, "$name.bin"), sizeOf(name)) }
    }

    /** True when [dir] holds exactly the files of an unused slot: their names and sizes, and no other file. */
    fun isUnused(dir: File): Boolean {
        val children = dir.listFiles() ?: return false
        if (children.any { !it.isFile }) return false
        val expected = NAMES.associate { "$it.bin" to sizeOf(it) } + (WALLET_KEY_FILE_NAME to KEY_FILE_BYTES)
        return children.associate { it.name to it.length().toInt() } == expected
    }

    /**
     * Brings the folder of a wallet that [walletKey] owns to the layout. A record in the layout is kept. A record of an earlier build is
     * sealed again at its new size, if it fits. A record the wallet does not have is random bytes of its size, and so is a damaged one
     * that is not the layout's size.
     */
    fun settle(dir: File, walletId: String, walletKey: ByteArray) {
        for (name in NAMES) {
            val file = File(dir, "$name.bin")
            val size = sizeOf(name)
            val capacity = requireNotNull(capacityOf(name))
            val bytes = if (file.isFile) file.readBytes() else null
            val current = bytes?.let { open(walletKey, walletId, name, it) }
            if (current != null) {
                current.fill(0)
                continue
            }
            if (bytes != null) {
                val legacy = legacyOpen(walletKey, walletId, name, bytes)
                if (legacy != null) {
                    try {
                        if (legacy.size <= capacity) writeAtomically(file, seal(walletKey, walletId, name, legacy))
                    } finally {
                        legacy.fill(0)
                    }
                    continue
                }
                // Random bytes of the layout's size already read as absent, so they are left as they are.
                if (bytes.size == size) continue
            }
            writeRandom(file, size)
        }
    }

    /** Replaces [file] with random bytes of [size], and syncs the old bytes away first. A missing file is created. */
    private fun writeRandom(file: File, size: Int) {
        overwriteInPlace(file)
        file.writeBytes(randomBlob(size))
    }

    /** Random bytes that start with the version byte, so an unused file reads like a sealed record of another wallet. */
    private fun randomBlob(size: Int): ByteArray = ByteArray(size).also {
        random.nextBytes(it)
        it[0] = BLOB_VERSION
    }

    internal fun legacyAad(walletId: String, name: String): ByteArray =
        (walletId + "\u0000" + name).toByteArray(StandardCharsets.UTF_8)

    private fun paddedAad(walletId: String, name: String): ByteArray =
        ("padded\u0000" + walletId + "\u0000" + name).toByteArray(StandardCharsets.UTF_8)
}
