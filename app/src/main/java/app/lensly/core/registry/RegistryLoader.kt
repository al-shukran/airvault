package app.lensly.core.registry

import android.content.res.AssetManager
import java.io.FileNotFoundException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Builds a [Registry] from the baked-in JSON catalogs.
 *
 * The main catalogs are required. The debug overlays are optional: they exist only in the debug
 * source set, so release builds never see a testnet entry. An overlay entry replaces a main entry
 * with the same chain ID (networks) or chain ID and contract (tokens), and otherwise adds to it.
 */
object RegistryLoader {
    const val NETWORKS_PATH = "registry/networks.json"
    const val TOKENS_PATH = "registry/tokens.json"
    const val NETWORKS_DEBUG_PATH = "registry/networks.debug.json"
    const val TOKENS_DEBUG_PATH = "registry/tokens.debug.json"

    /** [readAsset] returns the UTF-8 text at a published asset path, or null when the asset is absent. */
    fun load(readAsset: (String) -> String?): Registry {
        val networks = parseNetworks(
            NETWORKS_PATH,
            checkNotNull(readAsset(NETWORKS_PATH)) { "missing asset $NETWORKS_PATH" },
        )
        val tokens = parseTokens(
            TOKENS_PATH,
            checkNotNull(readAsset(TOKENS_PATH)) { "missing asset $TOKENS_PATH" },
        )
        val networkOverlay = readAsset(NETWORKS_DEBUG_PATH)
            ?.let { parseNetworks(NETWORKS_DEBUG_PATH, it) }.orEmpty()
        val tokenOverlay = readAsset(TOKENS_DEBUG_PATH)
            ?.let { parseTokens(TOKENS_DEBUG_PATH, it) }.orEmpty()

        return Registry(
            networks = (networks + networkOverlay).associateBy { it.chainId }.values.toList(),
            tokens = (tokens + tokenOverlay).associateBy { it.chainId to it.contract }.values.toList(),
        )
    }

    /** Loads from the APK's assets. Debug overlays are picked up only when the build ships them. */
    fun fromAssets(assets: AssetManager): Registry = load { path ->
        try {
            assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            null
        }
    }

    private val json = Json

    private fun parseNetworks(path: String, text: String): List<NetworkEntry> {
        val entries = json.decodeFromString<NetworksFile>(text).networks
        require(entries.map { it.chainId }.toSet().size == entries.size) {
            "duplicate chainId in $path"
        }
        return entries
    }

    private fun parseTokens(path: String, text: String): List<TokenEntry> {
        val entries = json.decodeFromString<TokensFile>(text).tokens
        require(entries.map { it.chainId to it.contract }.toSet().size == entries.size) {
            "duplicate token in $path"
        }
        return entries
    }
}

@Serializable
private class NetworksFile(val networks: List<NetworkEntry>)

@Serializable
private class TokensFile(val tokens: List<TokenEntry>)
