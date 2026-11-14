package app.lensly.app

import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultSession

/**
 * Holds the one open [VaultSession], and the [SessionAttachment] kept for it. [lock] closes the session, which zeroes the Vault Key, and
 * drops the attachment with it.
 *
 * An unlock takes [unlockEpoch] before it starts and hands it to [adopt] when it finishes. If the vault was locked
 * in between, the late session is closed and never kept, so a lock always wins.
 *
 * [attach] makes the state that an app keeps for each open session. The shared cover keeps none, so it defaults to nothing.
 */
class VaultHolder(
    val vault: Vault,
    private val attach: (VaultSession) -> SessionAttachment? = { null },
) {
    private var session: VaultSession? = null
    private var attachment: SessionAttachment? = null
    private var epoch = 0L

    /** The open session, or null while the vault is locked. */
    @Synchronized
    fun openSession(): VaultSession? = session

    /** The attachment kept for the open session, or null while the vault is locked or the app keeps none. */
    @Synchronized
    fun sessionAttachment(): SessionAttachment? = attachment

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
        attachment = attach(opened)
        return true
    }

    /** Closes the open session, zeroes its Vault Key and drops its attachment. Any unlock still running will not keep its session. */
    @Synchronized
    fun lock() {
        epoch++
        session?.close()
        session = null
        attachment = null
    }
}

/** State that an app keeps for one open session, such as Lensly's reads of its wallets. [VaultHolder] drops it on lock. */
interface SessionAttachment
