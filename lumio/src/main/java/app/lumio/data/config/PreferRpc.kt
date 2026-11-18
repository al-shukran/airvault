package app.lumio.data.config

import app.lensly.core.vault.VaultSession

/**
 * The owner's "Prefer RPC over Explorers" choice, kept as a vault record. It is off when nothing is stored, so balance reads ask the explorers
 * first, as they always have.
 */
object PreferRpcStore {
    /** The vault record that holds the choice. */
    const val RECORD = "lumio-prefer-rpc"

    private val ON = byteArrayOf(1)
    private val OFF = byteArrayOf(0)

    fun read(session: VaultSession): Boolean = session.getVaultRecord(RECORD)?.contentEquals(ON) == true

    fun write(session: VaultSession, on: Boolean) {
        session.putVaultRecord(RECORD, if (on) ON else OFF)
    }
}
