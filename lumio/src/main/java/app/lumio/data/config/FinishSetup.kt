package app.lumio.data.config

import app.lensly.core.vault.VaultSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The items of Settings' "Finish Setting Up" checklist, in the order it lists them: the duress password and device protection, which the first run
 * offers and the owner may skip, and pairing with Lensly.
 */
@Serializable
enum class FinishItem { DURESS, DEVICE_PROTECTION, PAIR }

/**
 * What the checklist has ticked off. A step ticks its item when it is done, and an item stays ticked, except that a step which ticked it first and
 * then failed takes its own tick back ([FinishSetupStore.untick]). So once every item is ticked the checklist is complete, and it stays hidden for good.
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
    fun read(session: VaultSession): FinishSetup = try {
        load(session)
    } catch (e: UnreadableRecordException) {
        FinishSetup()
    }

    /** The checklist stored in [session], or nothing ticked when none is stored. @throws UnreadableRecordException when the record does not parse. */
    private fun load(session: VaultSession): FinishSetup {
        val bytes = session.getVaultRecord(RECORD) ?: return FinishSetup()
        return decodeOrRefuse("setup checklist") { json.decodeFromString(FinishSetup.serializer(), bytes.decodeToString()) }
    }

    /**
     * Ticks [item] off in [session]. Ticking an item that is already ticked changes nothing. A stored record that does not parse is left as it is, and
     * this throws [UnreadableRecordException], so one tick never resets the other items.
     */
    fun tick(session: VaultSession, item: FinishItem) {
        val current = load(session)
        if (item in current.done) return
        val next = current.copy(done = current.done + item)
        session.putVaultRecord(RECORD, json.encodeToString(FinishSetup.serializer(), next).encodeToByteArray())
    }

    /** Takes the tick of [item] back, for a step that ticked it before its work and then failed. Does nothing when [item] is not ticked. */
    fun untick(session: VaultSession, item: FinishItem) {
        val current = load(session)
        if (item !in current.done) return
        session.putVaultRecord(RECORD, json.encodeToString(FinishSetup.serializer(), current.copy(done = current.done - item)).encodeToByteArray())
    }
}
