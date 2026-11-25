package app.lumio.data.config

import app.lensly.core.vault.VaultSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the transaction screens do, as the owner set it. It is a vault record, so it is encrypted under the vault key. A missing field reads as its default.
 *
 * [hideSpam] keeps spam and scam transfers out of Activity, and it is on until the owner turns it off. [lastChanceSeconds] is the pause after Lensly
 * signs a send and before Lumio broadcasts it, in which the owner can still stop it. Zero sends at once.
 */
@Serializable
data class TransactionSettings(
    val hideSpam: Boolean = true,
    val lastChanceSeconds: Int = DEFAULT_LAST_CHANCE_SECONDS,
) {
    /** Whether Activity reads include spam. Hide Spam on leaves it out; the owner can still show it there, for the session. */
    val activityIncludesSpam: Boolean
        get() = !hideSpam

    companion object {
        /** The default pause before a send is broadcast, in seconds. */
        const val DEFAULT_LAST_CHANCE_SECONDS = 60

        /** The choices of the Last Chance pause, in seconds, up to five minutes, so there is ample time to stop a signed send. */
        val LAST_CHANCE_CHOICES: List<Int> = listOf(0, 1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 300)
    }
}

/** Reads and writes [TransactionSettings] as the vault record [TransactionSettingsStore.RECORD]. */
object TransactionSettingsStore {
    /** The vault record that holds these settings. */
    const val RECORD = "lumio-transaction-settings"

    private val json = Json

    /** The settings stored in [session], or the defaults when none are stored or the record does not parse. */
    fun read(session: VaultSession): TransactionSettings = try {
        load(session)
    } catch (e: UnreadableRecordException) {
        TransactionSettings()
    }

    /**
     * The settings stored in [session], or the defaults when none are stored. A pause not among [TransactionSettings.LAST_CHANCE_CHOICES] reads as the
     * default pause. @throws UnreadableRecordException when the record does not parse.
     */
    private fun load(session: VaultSession): TransactionSettings {
        val bytes = session.getVaultRecord(RECORD) ?: return TransactionSettings()
        val stored = decodeOrRefuse("transaction settings") { json.decodeFromString(TransactionSettings.serializer(), bytes.decodeToString()) }
        val seconds = stored.lastChanceSeconds.takeIf { it in TransactionSettings.LAST_CHANCE_CHOICES } ?: TransactionSettings.DEFAULT_LAST_CHANCE_SECONDS
        return stored.copy(lastChanceSeconds = seconds)
    }

    /** Saves [settings]. A stored record that does not parse is left as it is, and this throws [UnreadableRecordException]. */
    fun write(session: VaultSession, settings: TransactionSettings) {
        load(session)
        session.putVaultRecord(RECORD, json.encodeToString(TransactionSettings.serializer(), settings).encodeToByteArray())
    }
}
