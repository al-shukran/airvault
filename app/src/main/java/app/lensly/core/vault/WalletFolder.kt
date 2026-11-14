package app.lensly.core.vault

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey

/**
 * The files of a wallet folder, and how a record is sealed in one.
 *
 * Every wallet folder holds the same files with the same sizes, whether it belongs to the real vault, to the Decoy Vault, or is an
 * unused slot: `pwk.bin`, then `<name>.bin` for each record name in [NAMES]. A record that a wallet does not have is random bytes of its
 * file's size, and a record that it has is sealed to that same size. So the folder shape says nothing about the wallet inside.
 *
 * A sealed record holds the record's length, the record, and random filler up to the record's capacity. Random bytes never authenticate,
 * so [open] returns null for an absent record, and a random file is never read as a wallet's.
 *
 * `pwk.bin` holds the folder's Per-Wallet Key in two layers. The inner layer is the key sealed under the Vault Key, with the wallet id as
 * AAD. The outer layer seals that under the folder's own Keystore key, and the file names that key in clear: `version (1) || key id (36)
 * || AES-GCM_folder(inner)`. The key id is random and names no wallet. Deleting the Keystore key makes the file unreadable, even with the
 * Vault Key, so a shred does not depend on the storage erasing old bytes. Every folder has a key. An unused slot's inner layer is random
 * bytes sealed under its own key, so every outer layer opens and the keys do not show which folders hold wallets.
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

    /** The length of a folder's key id: a UUID, written as ASCII. */
    private const val KEY_ID_BYTES = 36

    /** The plaintext each fixed record can hold. A record that does not fit is refused. */
    private val CAPACITY: Map<String, Int> = mapOf(META to 256, SECRET to 2048, BASE_NAME to 128, PROFILE to 512)

    /**
     * The inner layer of `pwk.bin`: the Per-Wallet Key sealed under the Vault Key. An earlier build wrote this alone as the whole file, so
     * an earlier file has this size.
     */
    internal const val VAULT_WRAPPED_KEY_BYTES = 1 + Aead.IV_BYTES + KEY_BYTES + TAG_BYTES

    /** The size of `pwk.bin`, a real wallet's key file and an unused slot's alike. */
    internal const val KEY_FILE_BYTES = 1 + KEY_ID_BYTES + Aead.IV_BYTES + VAULT_WRAPPED_KEY_BYTES + TAG_BYTES

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

    /**
     * Makes [dir] an unused slot. Its Keystore key goes first, so the wallet it held can no longer be opened. The other files are then
     * overwritten with random bytes of the layout's sizes, and the folder gets a key of its own with a random inner layer. A file that is
     * not in the layout is removed. Returns false when the old key could not be deleted: the Keystore failed, and the next unlock deletes
     * that key, since no folder names it any more.
     *
     * @throws IOException if a file cannot be read or written.
     */
    fun makeUnused(dir: File, keys: KeystoreBackend): Boolean {
        dir.mkdirs()
        val erased = retireKey(dir, keys)
        val wanted = NAMES.map { "$it.bin" }.toSet() + WALLET_KEY_FILE_NAME
        dir.listFiles().orEmpty().forEach { child ->
            if (!child.isFile || child.name !in wanted) {
                if (child.isFile) overwriteInPlace(child)
                child.deleteRecursively()
            }
        }
        writeUnusedKeyFile(dir, keys)
        NAMES.forEach { name -> writeRandom(File(dir, "$name.bin"), sizeOf(name)) }
        return erased
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

    /**
     * The Per-Wallet Key of the wallet folder [dir], or null when [walletId] does not own it under [vaultKey]: the folder has no key
     * file, its key file is another vault's or damaged, or its Keystore key is gone. A key file of an earlier build, which has the inner
     * layer alone, is read too. The caller zeroes the result.
     *
     * @throws VaultException if the key file cannot be read or the Keystore fails. Neither is a verdict that the folder is not owned, so a
     * caller that deletes folders stops instead of deleting one it cannot check.
     */
    fun walletKeyOf(keys: KeystoreBackend, dir: File, vaultKey: ByteArray, walletId: String): ByteArray? {
        val file = File(dir, WALLET_KEY_FILE_NAME)
        if (!file.isFile) return null
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            throw VaultException("The key file of wallet $walletId could not be read.", e)
        }
        val inner = when (bytes.size) {
            VAULT_WRAPPED_KEY_BYTES -> bytes
            KEY_FILE_BYTES -> openOuterLayer(keys, bytes) ?: return null
            else -> return null
        }
        return try {
            Aead.open(vaultKey, walletId.toByteArray(StandardCharsets.UTF_8), decodeBlob(inner))
        } catch (e: AEADBadTagException) {
            null
        } catch (e: VaultException) {
            null
        }
    }

    /**
     * Writes the key file of the wallet folder [dir]: [walletKey] sealed under [vaultKey] with [walletId] as AAD, and that sealed under
     * the folder's Keystore key. A folder keeps the key it has. An unused slot's key is reused for its new wallet, and a folder with no
     * key gets one. The write is atomic.
     */
    fun writeKeyFile(dir: File, keys: KeystoreBackend, vaultKey: ByteArray, walletId: String, walletKey: ByteArray) {
        val keyId = keyIdIn(dir) ?: UUID.randomUUID().toString()
        val folderKey = keystoreCall { keys.folderKey(keyId) ?: keys.createFolderKey(keyId) }
        val inner = encodeBlob(Aead.seal(vaultKey, walletId.toByteArray(StandardCharsets.UTF_8), walletKey))
        writeAtomically(File(dir, WALLET_KEY_FILE_NAME), keyFileOf(keyId, folderKey, inner))
    }

    /**
     * Retires the Keystore key of the wallet folder [dir], so that its key file can no longer be opened. Returns false when the Keystore
     * could not delete it. A folder with no key file in the current layout has no key to delete.
     *
     * @throws IOException if the key file cannot be read.
     */
    fun retireKey(dir: File, keys: KeystoreBackend): Boolean {
        val keyId = keyIdIn(dir) ?: return true
        return try {
            keystoreCall { keys.deleteFolderKey(keyId) }
            true
        } catch (e: VaultException) {
            false
        }
    }

    /**
     * Wraps the key file of each wallet folder that an earlier build wrote, which is the inner layer alone, under a Keystore key of
     * the folder's own. The inner layer is kept as it is, so no vault key is needed, and every folder is wrapped whichever vault
     * unlocks. A folder that cannot be changed keeps its old file, and the next unlock tries again.
     */
    fun upgradeKeyFiles(wallets: File, keys: KeystoreBackend) {
        for (dir in folderDirs(wallets)) {
            val file = File(dir, WALLET_KEY_FILE_NAME)
            if (!file.isFile || file.length() != VAULT_WRAPPED_KEY_BYTES.toLong()) continue
            try {
                val keyId = UUID.randomUUID().toString()
                val folderKey = keystoreCall { keys.createFolderKey(keyId) }
                writeAtomically(file, keyFileOf(keyId, folderKey, file.readBytes()))
            } catch (e: IOException) {
                // Left as it was. The next unlock tries again.
            } catch (e: VaultException) {
                // Same as above.
            }
        }
    }

    /**
     * Brings the Keystore keys of the wallet folders in line with the folders. A folder whose key is gone is made an unused slot again, so
     * it gets a key of its own. Then every key that no folder names is deleted. Those are keys that a failed deletion left behind, and keys
     * that a crash left unnamed. Nothing is deleted when a key file cannot be read, since its key may be the one that is named.
     */
    fun reconcileFolderKeys(wallets: File, keys: KeystoreBackend) {
        val dirs = folderDirs(wallets)
        for (dir in dirs) {
            val keyId = try {
                keyIdIn(dir)
            } catch (e: IOException) {
                null
            } ?: continue
            if (!hasKey(keys, keyId)) {
                try {
                    makeUnused(dir, keys)
                } catch (e: IOException) {
                    // Left as it is. The next unlock tries again.
                } catch (e: VaultException) {
                    // Same as above.
                }
            }
        }
        val named = try {
            dirs.mapNotNullTo(mutableSetOf()) { keyIdIn(it) }
        } catch (e: IOException) {
            return
        }
        try {
            keystoreCall { keys.folderKeyIds() }
                .filter { it !in named }
                .forEach { id -> keystoreCall { keys.deleteFolderKey(id) } }
        } catch (e: VaultException) {
            // Left for the next unlock.
        }
    }

    /** False only when the Keystore holds no key under [keyId]. A Keystore error counts as a key, so no key is replaced by mistake. */
    private fun hasKey(keys: KeystoreBackend, keyId: String): Boolean = try {
        keystoreCall { keys.folderKey(keyId) } != null
    } catch (e: VaultException) {
        true
    }

    /**
     * Writes the key file of an unused slot: a key of its own, and a random inner layer sealed under it, so the file opens with its key and
     * reads as a wallet's. If the Keystore refuses the key, the file is random bytes behind an id. The next unlock gives the folder a key.
     */
    private fun writeUnusedKeyFile(dir: File, keys: KeystoreBackend) {
        val keyId = UUID.randomUUID().toString()
        val bytes = try {
            keyFileOf(keyId, keystoreCall { keys.createFolderKey(keyId) }, randomBlob(VAULT_WRAPPED_KEY_BYTES))
        } catch (e: VaultException) {
            byteArrayOf(BLOB_VERSION) + keyId.toByteArray(StandardCharsets.US_ASCII) + randomBlob(KEY_FILE_BYTES - 1 - KEY_ID_BYTES)
        }
        val file = File(dir, WALLET_KEY_FILE_NAME)
        overwriteInPlace(file)
        file.writeBytes(bytes)
    }

    /** The inner layer of a current key file, opened with the folder's Keystore key. Null when the layer does not open. */
    private fun openOuterLayer(keys: KeystoreBackend, bytes: ByteArray): ByteArray? {
        if (bytes[0] != BLOB_VERSION) return null
        val keyId = keyIdOf(bytes)
        val folderKey = keystoreCall { keys.folderKey(keyId) } ?: return null
        return try {
            Aead.openWithKeystore(folderKey, keyAad(keyId), bytes.copyOfRange(1 + KEY_ID_BYTES, bytes.size))
        } catch (e: AEADBadTagException) {
            null
        } catch (e: GeneralSecurityException) {
            throw VaultException("The Keystore could not open a wallet folder key file.", e)
        } catch (e: ProviderException) {
            throw VaultException("The Keystore could not open a wallet folder key file.", e)
        }
    }

    /** The key file of folder [keyId]: the version byte, the key id in clear, then [inner] sealed under the folder's [folderKey]. */
    private fun keyFileOf(keyId: String, folderKey: SecretKey, inner: ByteArray): ByteArray {
        val sealed = keystoreCall { Aead.sealWithKeystore(folderKey, keyAad(keyId), inner) }
        return byteArrayOf(BLOB_VERSION) + keyId.toByteArray(StandardCharsets.US_ASCII) + sealed
    }

    /** The key id that the key file of the folder in [dir] names, or null when the folder has no key file in the current layout. */
    private fun keyIdIn(dir: File): String? {
        val file = File(dir, WALLET_KEY_FILE_NAME)
        if (!file.isFile) return null
        val bytes = file.readBytes()
        return if (bytes.size == KEY_FILE_BYTES && bytes[0] == BLOB_VERSION) keyIdOf(bytes) else null
    }

    /** The key id in the current key file [bytes], which the caller has checked the size of. */
    private fun keyIdOf(bytes: ByteArray): String = String(bytes, 1, KEY_ID_BYTES, StandardCharsets.US_ASCII)

    private fun keyAad(keyId: String): ByteArray = "lensly.vault.folder-key\u0000$keyId".toByteArray(StandardCharsets.US_ASCII)

    /** The wallet folders under [wallets]. */
    private fun folderDirs(wallets: File): List<File> = wallets.listFiles().orEmpty().filter { it.isDirectory }

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
