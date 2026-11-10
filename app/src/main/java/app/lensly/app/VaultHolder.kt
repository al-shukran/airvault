package app.lensly.app

import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultSession

/**
 * Holds the one open [VaultSession]. [lock] closes it, which zeroes the Vault Key.
 *
 * An unlock takes [unlockEpoch] before it starts and hands it to [adopt] when it finishes. If the vault was locked
 * in between, the late session is closed and never kept, so a lock always wins.
 */
class VaultHolder(val vault: Vault) {
    private var session: VaultSession? = null
    private var epoch = 0L

    /** The open session, or null while the vault is locked. */
    @Synchronized
    fun openSession(): VaultSession? = session

    /** Marks the start of an unlock. Pass the value to [adopt]. */
    @Synchronized
    fun unlockEpoch(): Long = epoch

    /**
     * Keeps [opened] as the open session. Returns false, and closes [opened], if the vault was locked after [epoch]
     * was read.
     */
    @Synchronized
    fun adopt(opened: VaultSession, epoch: Long): Boolean {
        if (epoch != this.epoch) {
            opened.close()
            return false
        }
        session?.close()
        session = opened
        return true
    }

    /** Closes the open session and zeroes its Vault Key. Any unlock still running will not keep its session. */
    @Synchronized
    fun lock() {
        epoch++
        session?.close()
        session = null
    }
}
