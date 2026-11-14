package app.lensly.core.registry

import android.content.res.AssetManager
import java.io.FileNotFoundException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Builds a [Registry] from the baked-in JSON catalogs.
 *
 * The main networks and tokens are required. The whitelist, the denylist, the wallets and the debug overlays are optional: a build
 * without a whitelist approves no contract call, which is the safe reading of a missing list. The shipped catalog always carries a
 * denylist and wallets, and DenylistTest and WalletEntryTest check the shipped files. The debug overlays exist only in the debug source
 * set, so release builds never see a testnet entry. An overlay entry replaces a main entry with the same chain ID (networks)
 * or chain ID and contract (tokens), and otherwise adds to it. A whitelist overlay only adds entries, and a repeated whitelist
 * entry is refused.
 */
object RegistryLoader {
    const val NETWORKS_PATH = "registry/networks.json"
    const val TOKENS_PATH = "registry/tokens.json"
    const val WHITELIST_PATH = "registry/whitelist.json"
    const val DENYLIST_PATH = "registry/denylist.json"
    const val WALLETS_PATH = "registry/wallets.json"
    const val NETWORKS_DEBUG_PATH = "registry/networks.debug.json"
    const val TOKENS_DEBUG_PATH = "registry/tokens.debug.json"
    const val WHITELIST_DEBUG_PATH = "registry/whitelist.debug.json"

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
        val whitelistFile = readAsset(WHITELIST_PATH)?.let { parseWhitelist(WHITELIST_PATH, it) }
        val whitelist = whitelistFile?.whitelist.orEmpty()
        val networkOverlay = readAsset(NETWORKS_DEBUG_PATH)
            ?.let { parseNetworks(NETWORKS_DEBUG_PATH, it) }.orEmpty()
        val tokenOverlay = readAsset(TOKENS_DEBUG_PATH)
            ?.let { parseTokens(TOKENS_DEBUG_PATH, it) }.orEmpty()
        val whitelistOverlay = readAsset(WHITELIST_DEBUG_PATH)
            ?.let { parseWhitelist(WHITELIST_DEBUG_PATH, it).whitelist }.orEmpty()
        val denylist = readAsset(DENYLIST_PATH)?.let { json.decodeFromString<DenylistFile>(it).denylist }.orEmpty()
        val wallets = readAsset(WALLETS_PATH)?.let { json.decodeFromString<WalletsFile>(it).wallets }.orEmpty()

        return Registry(
            networks = (networks + networkOverlay).associateBy { it.chainId }.values.toList(),
            tokens = (tokens + tokenOverlay).associateBy { it.chainId to it.contract }.values.toList(),
            whitelist = whitelist + whitelistOverlay,
            catalogSequence = whitelistFile?.sequence ?: 0L,
            denylist = denylist,
            wallets = wallets,
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

    private fun parseWhitelist(path: String, text: String): WhitelistFile =
        json.decodeFromString<WhitelistFile>(text).also {
            require(it.whitelist.map { entry -> Triple(entry.chainId, entry.contract, entry.selector) }.toSet().size == it.whitelist.size) {
                "duplicate whitelist entry in $path"
            }
        }
}

@Serializable
private class NetworksFile(val networks: List<NetworkEntry>)

@Serializable
private class TokensFile(val tokens: List<TokenEntry>)

@Serializable
private class WhitelistFile(
    /** The catalog sequence of the build's whitelist, so an installed package can be compared with it. */
    val sequence: Long = 0,
    val whitelist: List<WhitelistEntry>,
)

@Serializable
private class DenylistFile(val denylist: List<DenylistEntry>)

@Serializable
private class WalletsFile(val wallets: List<WalletEntry>)
