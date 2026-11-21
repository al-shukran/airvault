package app.lensly.core.vault

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * The photo key pair of photos taken before the first setup. Until a vault exists, a shot is sealed to the public half in `real.pub`,
 * as every photo is. The private half waits in [VaultLayout.pendingKeyFile], wrapped under the Keystore key of
 * [KeystoreBackend.pendingPhotoKey], so no plain copy is on disk. When the vault is created it adopts the pair (see
 * [VaultSession.adoptPendingPhotoKey]), so those photos open through the vault's photo key from then on. A pair that cannot be verified is
 * erased with the photos sealed to it, since nothing can open them. A wipe removes the pending file with the photo folder, and the Keystore
 * key with the vault's other keys.
 */
internal object PendingPhotoKey {
    private val AAD = "lensly.vault.pending-photo-key".toByteArray(Charsets.US_ASCII)
    private val lock = Any()

    /**
     * Makes the pair when no vault exists and no usable pair does. It is best effort: when no pair can be made, no public key is left to
     * seal to, so the save that follows fails, as it did before.
     */
    fun ensure(layout: VaultLayout, keys: KeystoreBackend) {
        synchronized(lock) {
            if (!layout.isBeforeSetup()) return
            read(layout, keys)?.let { existing ->
                existing.fill(0)
                return
            }
            try {
                val privateKey = MediaEnvelope.newPrivateKey()
                try {
                    val sealed = keystoreCall { Aead.sealWithKeystore(keys.createPendingPhotoKey(), AAD, privateKey) }
                    layout.photos.mkdirs()
                    writeAtomically(layout.pendingKeyFile, sealed)
                    writeAtomically(layout.photoKeyFile(decoy = false), MediaEnvelope.publicKeyOf(privateKey))
                } finally {
                    privateKey.fill(0)
                }
            } catch (e: VaultException) {
                // Nothing usable was made, so the save that follows finds no public key.
            } catch (e: IOException) {
                // Same as above.
            }
        }
    }

    /**
     * The private half of the pending pair, or null when there is none. A pair that cannot be verified is erased, and so is a photo folder
     * that has no pair to open it. The caller zeroes the result.
     */
    fun read(layout: VaultLayout, keys: KeystoreBackend): ByteArray? = synchronized(lock) {
        val privateKey = if (layout.pendingKeyFile.isFile) openPair(layout, keys) else null
        if (privateKey == null) erase(layout, keys)
        privateKey
    }

    /** Erases the pending pair, the photos sealed to it, and the public half they were sealed to. */
    internal fun erase(layout: VaultLayout, keys: KeystoreBackend) {
        synchronized(lock) {
            layout.photoDir(decoy = false).walkTopDown().filter { it.isFile }.forEach(::shred)
            layout.photoDir(decoy = false).deleteRecursively()
            layout.photoKeyFile(decoy = false).delete()
            discard(layout, keys)
        }
    }

    /** Deletes the pending private half and its Keystore key. The photos sealed to the pair stay where they are. */
    internal fun discard(layout: VaultLayout, keys: KeystoreBackend) {
        shred(layout.pendingKeyFile)
        try {
            keystoreCall { keys.deletePendingPhotoKey() }
        } catch (e: VaultException) {
            // The private half is gone, and the Keystore key opens nothing without it.
        }
    }

    /** The private half, when the pending pair opens and matches `real.pub`. Null when it cannot be verified. */
    private fun openPair(layout: VaultLayout, keys: KeystoreBackend): ByteArray? {
        return try {
            val wrapKey = keystoreCall { keys.pendingPhotoKey() } ?: return null
            val privateKey = keystoreCall { Aead.openWithKeystore(wrapKey, AAD, layout.pendingKeyFile.readBytes()) }
            val matches = privateKey.size == MediaEnvelope.KEY_BYTES &&
                MediaEnvelope.publicKeyOf(privateKey).contentEquals(layout.photoKeyFile(decoy = false).readBytes())
            if (matches) {
                privateKey
            } else {
                privateKey.fill(0)
                null
            }
        } catch (e: VaultException) {
            null
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Overwrites [file] with random bytes, then deletes it. */
    private fun shred(file: File) {
        try {
            overwriteInPlace(file)
        } catch (e: IOException) {
            // Deleted below all the same.
        }
        file.delete()
    }
}

/** [PendingPhotoKey.ensure] for the app's vault folder and Keystore. The camera calls it on its sealing thread, before a shot is saved. */
internal fun ensurePendingPhotoKey(context: Context) {
    PendingPhotoKey.ensure(VaultLayout.of(context), AndroidKeystoreBackend(context.applicationContext))
}

/** [PendingPhotoKey.read] for the app's vault folder and Keystore. The caller zeroes the result. */
internal fun readPendingPhotoKey(context: Context): ByteArray? =
    PendingPhotoKey.read(VaultLayout.of(context), AndroidKeystoreBackend(context.applicationContext))
