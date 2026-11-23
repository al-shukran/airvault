package app.lumio.data.config

import app.lensly.core.vault.VaultSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The items of Settings' "Finish Setting Up" checklist, in the order it lists them: the duress password and device protection, which the first run
 * offers and the owner may skip, and pairing with Lensly.
 */
@Serializable
enum class FinishItem { DURESS, DEVICE_PROTECTION, PAIR }

/**
 * What the checklist has ticked off. A step ticks its item when it is done, and an item is never unticked. So once every item is ticked the checklist
 * is complete, and it stays hidden for good.
 */
@Serializable
data class FinishSetup(val done: Set<FinishItem> = emptySet()) {
    /** The items not ticked yet, in the order of the checklist. */
    val open: List<FinishItem> get() = FinishItem.entries.filter { it !in done }

    /** True once every item is ticked. */
    val complete: Boolean get() = open.isEmpty()
}

/**
 * Reads and writes [FinishSetup] as the vault record [FinishSetupStore.RECORD], under the vault key like the other settings. The checklist names
 * what the vault holds, so it is not kept in a plain preference file.
 */
object FinishSetupStore {
    /** The vault record that holds the checklist. */
    const val RECORD = "lumio-finish-setup"

    private val json = Json

    /** The checklist as stored in [session], or nothing ticked when none is stored or the record does not parse. */
    fun read(session: VaultSession): FinishSetup {
        val bytes = session.getVaultRecord(RECORD) ?: return FinishSetup()
        return try {
            json.decodeFromString(FinishSetup.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            FinishSetup()
        }
    }

    /** Ticks [item] off in [session]. Ticking an item that is already ticked changes nothing. */
    fun tick(session: VaultSession, item: FinishItem) {
        val current = read(session)
        if (item in current.done) return
        val next = current.copy(done = current.done + item)
        session.putVaultRecord(RECORD, json.encodeToString(FinishSetup.serializer(), next).encodeToByteArray())
    }
}
