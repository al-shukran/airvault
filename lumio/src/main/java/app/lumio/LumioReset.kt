package app.lumio

import app.lensly.app.VaultHolder
import app.lensly.core.vault.VaultLayout
import app.lumio.data.config.LockSettingsStore
import app.lumio.data.db.LumioDatabase
import java.io.File

/**
 * Erases every store Lumio keeps on this phone, for Reset Lumio: the App Password vault with its Keystore keys, which hold the pairings, the address
 * books, the Biometric Unlock key and the photos; the chain data database at [databaseFile], with its balances, scan jobs and history; and the lock
 * choices. The camera's Hidden Entry Gesture is kept, so the cover is the same after a reset, and Lensly is not touched. The vault locks first, so its
 * keys are zeroed before any file goes. The caller closes the database first. The database and the lock choices are erased even when the vault
 * cannot be deleted, and then the vault's [app.lensly.core.vault.VaultException] reaches the caller.
 */
internal fun eraseLumioStores(holder: VaultHolder, databaseFile: File, lock: LockSettingsStore) {
    holder.lock()
    try {
        holder.vault.wipe()
    } finally {
        listOf("", "-journal", "-wal", "-shm").forEach { File(databaseFile.path + it).delete() }
        lock.erase()
    }
}

/**
 * The chain data database. It sits in the vault's own folder, so every erase of the vault removes it as well: a wrong-password erase, an
 * unverifiable vault and Reset Lumio each delete that folder, after overwriting its files.
 */
internal fun lumioDatabaseFile(layout: VaultLayout): File = File(layout.root, LumioDatabase.FILE_NAME)
