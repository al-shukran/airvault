package app.lumio.data.config

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.vault.VaultSession
import app.lumio.data.esplora.CustomEsploraProvider
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The provider settings of the open vault, kept as the vault record [RECORD]. The vault seals the record under its key, so the API keys in it are
 * never stored in the clear.
 *
 * The Bitcoin server overrides of an earlier release, kept as [LEGACY_SERVERS_RECORD], become the owner's own servers the first time the settings are
 * read. Each override keeps its URL and its network, and nothing else changes for the owner.
 */
class ProviderConfigStore(private val session: VaultSession) {

    /** The saved settings by provider id. Empty when nothing is stored, or when the record does not parse. */
    fun load(): Map<String, ProviderConfig> {
        val bytes = session.getVaultRecord(RECORD) ?: return migrateLegacy()
        return try {
            json.decodeFromString(serializer, bytes.decodeToString())
        } catch (e: SerializationException) {
            emptyMap()
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
    }

    fun save(configs: Map<String, ProviderConfig>) {
        session.putVaultRecord(RECORD, json.encodeToString(serializer, configs).encodeToByteArray())
    }

    /** Turns the overrides of the earlier release into the owner's servers, and saves them. Nothing is saved when there are no overrides. */
    private fun migrateLegacy(): Map<String, ProviderConfig> {
        val servers = legacyServers()
        if (servers.isEmpty()) return emptyMap()
        val configs = servers.entries.associate { (network, url) ->
            CustomEsploraProvider.idFor(chainOf(network)) to ProviderConfig(baseUrl = url)
        }
        save(configs)
        return configs
    }

    /** The overrides of the earlier release, read only to migrate them. Empty when there are none, or the record does not read. */
    private fun legacyServers(): Map<BtcNetwork, String> = try {
        session.getVaultRecord(LEGACY_SERVERS_RECORD)?.let { decodeLegacy(it) } ?: emptyMap()
    } catch (e: IllegalStateException) {
        emptyMap()
    } catch (e: IOException) {
        emptyMap()
    } catch (e: IllegalArgumentException) {
        emptyMap()
    }

    /** The earlier record: a version byte, the count, then each override as a network code and its URL. */
    private fun decodeLegacy(bytes: ByteArray): Map<BtcNetwork, String> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readUnsignedByte() == LEGACY_VERSION) { "unknown servers version" }
        val count = input.readInt()
        require(count in 0..BtcNetwork.entries.size) { "server count out of range" }
        buildMap {
            repeat(count) {
                val network = BtcNetwork.entries.getOrNull(input.readUnsignedByte()) ?: throw IllegalArgumentException("unknown network")
                put(network, input.readUTF())
            }
        }
    }

    private fun chainOf(network: BtcNetwork): String = if (network == BtcNetwork.MAINNET) "mainnet" else "testnet4"

    companion object {
        /** The vault record that holds the settings. */
        const val RECORD = "lumio-providers"

        /** The vault record of the earlier release's Bitcoin server overrides. It is read only to migrate them. */
        const val LEGACY_SERVERS_RECORD = "btc-servers"

        private const val LEGACY_VERSION = 1

        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), ProviderConfig.serializer())
    }
}
