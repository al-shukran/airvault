package app.lumio

import app.lensly.app.VaultHolder
import app.lensly.core.vault.VaultLayout
import app.lumio.data.config.LockSettingsStore
import app.lumio.data.db.LumioDatabase
import java.io.File

/**
 * Erases every store Lumio keeps on this phone, for Reset Lumio: the App Password vault with its Keystore keys, which hold the pairings, the address
 * books, the Biometric Unlock key and the photos; the chain data database at [databaseFile], with its balances, scan jobs and history; the shared
 * receive code in [receiveShares]; and the lock choices. The camera's Hidden Entry Gesture is kept, so the cover is the same after a reset, and
 * Lensly is not touched. The vault locks first, so its keys are zeroed before any file goes. The caller closes the database first. The stores
 * outside the vault are erased even when the vault cannot be deleted, and then the vault's [app.lensly.core.vault.VaultException] reaches the caller.
 */
internal fun eraseLumioStores(holder: VaultHolder, databaseFile: File, receiveShares: File, lock: LockSettingsStore) {
    holder.lock()
    try {
        holder.vault.wipe()
    } finally {
        eraseOutsideVault(databaseFile, receiveShares, lock)
    }
}

/**
 * What Lumio erases after the vault erased itself, on the last allowed wrong try or an unverifiable file, while the vault's lock is held. The
 * open session locks first, so its Vault Key is zeroed and no screen or scan reads the closed database, then [closeDatabase] closes the
 * database handle, and the stores outside the vault go even when the close throws.
 */
internal fun eraseAfterVaultErase(
    holder: VaultHolder,
    closeDatabase: () -> Unit,
    databaseFile: File,
    receiveShares: File,
    lock: LockSettingsStore,
) {
    holder.lock()
    try {
        closeDatabase()
    } finally {
        eraseOutsideVault(databaseFile, receiveShares, lock)
    }
}

/**
 * The stores that the vault's wipe does not finish: the database files, which the wipe removes with the vault folder but which are deleted here
 * as well, in case one was open; the shared receive code in [receiveShares], which names an address; and the lock choices, which sit outside the
 * vault because the cover reads them before anyone unlocks. The vault's own erase after a wrong try runs this too (see [eraseAfterVaultErase]),
 * so every erase of Lumio leaves the same stores behind.
 */
internal fun eraseOutsideVault(databaseFile: File, receiveShares: File, lock: LockSettingsStore) {
    listOf("", "-journal", "-wal", "-shm").forEach { File(databaseFile.path + it).delete() }
    receiveShares.deleteRecursively()
    lock.erase()
}

/**
 * The chain data database. It sits in the vault's own folder, so every erase of the vault removes it as well: a wrong-password erase, an
 * unverifiable vault and Reset Lumio each delete that folder, after overwriting its files.
 */
internal fun lumioDatabaseFile(layout: VaultLayout): File = File(layout.root, LumioDatabase.FILE_NAME)
