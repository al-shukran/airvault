package app.lumio.data.config

import app.lensly.core.vault.VaultSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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
        const val DEFAULT_LAST_CHANCE_SECONDS = 3

        /** The choices of the Last Chance pause, in seconds: from 0 to 10, a tick for every second. */
        val LAST_CHANCE_CHOICES: List<Int> = (0..10).toList()
    }
}

/** Reads and writes [TransactionSettings] as the vault record [TransactionSettingsStore.RECORD]. */
object TransactionSettingsStore {
    /** The vault record that holds these settings. */
    const val RECORD = "lumio-transaction-settings"

    private val json = Json

    /**
     * The settings stored in [session], or the defaults when none are stored or the record does not parse. A pause not among
     * [TransactionSettings.LAST_CHANCE_CHOICES] reads as the default pause.
     */
    fun read(session: VaultSession): TransactionSettings {
        val bytes = session.getVaultRecord(RECORD) ?: return TransactionSettings()
        val stored = try {
            json.decodeFromString(TransactionSettings.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            return TransactionSettings()
        }
        val seconds = stored.lastChanceSeconds.takeIf { it in TransactionSettings.LAST_CHANCE_CHOICES } ?: TransactionSettings.DEFAULT_LAST_CHANCE_SECONDS
        return stored.copy(lastChanceSeconds = seconds)
    }

    fun write(session: VaultSession, settings: TransactionSettings) {
        session.putVaultRecord(RECORD, json.encodeToString(TransactionSettings.serializer(), settings).encodeToByteArray())
    }
}
