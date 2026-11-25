package app.lumio.data.config

import app.lensly.core.vault.VaultSession
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The provider settings of the open vault, kept as the vault record [RECORD]. The vault seals the record under its key, so the API keys in it are
 * never stored in the clear.
 */
class ProviderConfigStore(private val session: VaultSession) {

    /** The saved settings by provider id. Empty when nothing is stored, or when the record does not parse. */
    fun load(): Map<String, ProviderConfig> = try {
        stored()
    } catch (e: UnreadableRecordException) {
        emptyMap()
    }

    /** The saved settings by provider id. Empty when nothing is stored. @throws UnreadableRecordException when the record does not parse. */
    private fun stored(): Map<String, ProviderConfig> {
        val bytes = session.getVaultRecord(RECORD) ?: return emptyMap()
        return decodeOrRefuse("provider settings") { json.decodeFromString(serializer, bytes.decodeToString()) }
    }

    /** Saves [configs]. A stored record that does not parse is left as it is, and this throws [UnreadableRecordException]. */
    fun save(configs: Map<String, ProviderConfig>) {
        stored()
        session.putVaultRecord(RECORD, json.encodeToString(serializer, configs).encodeToByteArray())
    }

    companion object {
        /** The vault record that holds the settings. */
        const val RECORD = "lumio-providers"

        private val json = Json
        private val serializer = MapSerializer(String.serializer(), ProviderConfig.serializer())
    }
}
