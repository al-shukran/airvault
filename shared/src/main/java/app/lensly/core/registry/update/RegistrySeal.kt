package app.lensly.core.registry.update

import app.lensly.core.vault.KeystoreBackend
import app.lensly.core.vault.keystoreCall
import app.lensly.core.vault.keystoreOrNull
import app.lensly.core.vault.macOf
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The registry store's rollback state, bound to the Keystore the way the vault's failure state is (see FailureStore): the [Seal] in
 * `registry-seal` holds the accepted floor and the sequences of the packages that last carried a whitelist and a denylist, MACed under the Keystore key
 * of that floor. Each accepted package makes the key for its own sequence and then deletes every other, so the newest key's number is
 * the highest sequence this device has accepted, and it outlives the app's files. A seal verifies only while its own key is the newest.
 *
 * The seal also holds the SHA-256 of the installed package file and of the files that carry the kept whitelist and denylist, so a file
 * is used only when it is byte for byte the one this device accepted: a signed file planted in its place (by any key, revoked or not)
 * reads as unverified, never as the package that set the floor, and its revocations never count.
 *
 * With file access an attacker can replace, delete or copy back any file in the store, but not a Keystore key. So:
 * - a package swapped in below the floor, or an older whitelist carrier swapped in, no longer matches the sealed sequences or hashes;
 * - deleting the store, or the seal, leaves the newest key behind, which still names the floor, so an older package is refused;
 * - copying back a whole older store, seal included, leaves a seal whose key was deleted.
 * Every one of these reads as [Reading.Unverified]: the installed package is not used (the built-in catalog is, flagged), and an
 * install must reach the Keystore's floor, so signing is never blocked and an older package is never taken.
 *
 * A crash between making the new key and writing the new seal leaves a key newer than the seal, which reads as unverified until an
 * install of that sequence or a higher one seals it again. A Keystore that cannot list its keys also reads as unverified.
 */
internal object RegistrySeal {
    /** The file holding the seal, beside the installed package. */
    const val FILE = "registry-seal"

    /**
     * [floor]: the highest accepted sequence. [whitelistSequence] and [denylistSequence]: the sequence of the last package that carried
     * a whitelist, or a denylist, or 0. [installedSha256], [whitelistSha256] and [denylistSha256]: the SHA-256 of the file that holds the
     * installed package and each of those carriers, or [NONE] where there is none.
     */
    class Seal(
        val floor: Long,
        val whitelistSequence: Long,
        val denylistSequence: Long,
        val installedSha256: ByteArray,
        val whitelistSha256: ByteArray,
        val denylistSha256: ByteArray,
    )

    sealed interface Reading {
        /** A seal that verifies under the newest key. */
        class Sealed(val seal: Seal) : Reading

        /** No seal and no registry key: a store from before seals existed, or an empty one. [RegistryUpdate.adoptUnsealed] seals it once. */
        data object Unsealed : Reading

        /** Anything else: a seal that is missing, altered, older than the newest key, or unreadable, or a Keystore that gave no answer. */
        data object Unverified : Reading
    }

    private const val HASH_SIZE = 32
    private const val PAYLOAD_SIZE = 8 + 8 + 8 + 3 * HASH_SIZE
    private const val MAC_SIZE = 32

    fun read(dir: File, keys: KeystoreBackend): Reading {
        val newest = keystoreOrNull { keys.registryMacKeySeqs() } ?: return Reading.Unverified
        val file = File(dir, FILE)
        if (!file.isFile) return if (newest.isEmpty()) Reading.Unsealed else Reading.Unverified
        val bytes = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.size == PAYLOAD_SIZE + MAC_SIZE }
            ?: return Reading.Unverified
        val payload = bytes.copyOf(PAYLOAD_SIZE)
        val buffer = ByteBuffer.wrap(payload)
        val seal = Seal(
            floor = buffer.getLong(),
            whitelistSequence = buffer.getLong(),
            denylistSequence = buffer.getLong(),
            installedSha256 = ByteArray(HASH_SIZE).also(buffer::get),
            whitelistSha256 = ByteArray(HASH_SIZE).also(buffer::get),
            denylistSha256 = ByteArray(HASH_SIZE).also(buffer::get),
        )
        if (seal.floor != newest.maxOrNull()) return Reading.Unverified
        val key = keystoreOrNull { keys.registryMacKey(seal.floor) } ?: return Reading.Unverified
        val expected = keystoreOrNull { macOf(key, payload) } ?: return Reading.Unverified
        if (!MessageDigest.isEqual(bytes.copyOfRange(PAYLOAD_SIZE, bytes.size), expected)) return Reading.Unverified
        return Reading.Sealed(seal)
    }

    /** The hash a seal holds for a file that is not there. */
    val NONE = ByteArray(HASH_SIZE)

    /** The SHA-256 of [bytes], or [NONE] for null. */
    fun digest(bytes: ByteArray?): ByteArray = bytes?.let { MessageDigest.getInstance("SHA-256").digest(it) } ?: NONE

    /** Whether [bytes] are the file that [sha256] names. [NONE] names no file. */
    fun matches(bytes: ByteArray, sha256: ByteArray): Boolean = !sha256.contentEquals(NONE) && MessageDigest.isEqual(digest(bytes), sha256)

    /** The highest floor the Keystore holds a key for, or null when it holds none. Throws when the Keystore cannot list them. */
    fun newestFloor(keys: KeystoreBackend): Long? = keystoreCall { keys.registryMacKeySeqs() }.maxOrNull()

    /** Makes the key for [seal]'s floor and returns the bytes of the seal under it. Every other key stays until [commit]. */
    fun create(seal: Seal, keys: KeystoreBackend): ByteArray = keystoreCall {
        val payload = ByteBuffer.allocate(PAYLOAD_SIZE)
            .putLong(seal.floor).putLong(seal.whitelistSequence).putLong(seal.denylistSequence)
            .put(seal.installedSha256).put(seal.whitelistSha256).put(seal.denylistSha256).array()
        payload + macOf(keys.createRegistryMacKey(seal.floor), payload)
    }

    /** Deletes every key but the one of [keepFloor]. A Keystore error is ignored: a key left behind is deleted by the next install. */
    fun keepOnly(keepFloor: Long?, keys: KeystoreBackend) {
        keystoreOrNull { keys.deleteRegistryMacKeysExcept(keepFloor) }
    }
}
