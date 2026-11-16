package app.lumio.data.config

import app.lensly.core.vault.VaultSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the wallet screens show, as the owner set it. It is a vault record, so it is encrypted under the vault key. A missing field reads as its default.
 * A record saved before the minimum balance, the spam filter and the sort were removed still reads: the fields it no longer has are ignored.
 *
 * [showTotal] shows the combined balance of a wallet, and it is off until the owner turns it on. [showAssets] shows each account's balance.
 */
@Serializable
data class DisplaySettings(
    val showTotal: Boolean = false,
    val showAssets: Boolean = true,
)

/** Reads and writes [DisplaySettings] as the vault record [DisplaySettingsStore.RECORD]. */
object DisplaySettingsStore {
    /** The vault record that holds these settings. */
    const val RECORD = "lumio-display-settings"

    private val json = Json { ignoreUnknownKeys = true }

    /** The settings stored in [session], or the defaults when none are stored or the record does not parse. */
    fun read(session: VaultSession): DisplaySettings {
        val bytes = session.getVaultRecord(RECORD) ?: return DisplaySettings()
        return try {
            json.decodeFromString(DisplaySettings.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            DisplaySettings()
        }
    }

    fun write(session: VaultSession, settings: DisplaySettings) {
        session.putVaultRecord(RECORD, json.encodeToString(DisplaySettings.serializer(), settings).encodeToByteArray())
    }
}
