package app.lensly.core.registry.update

import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.registry.WhitelistEntry

/**
 * The verified contents of a registry package: the networks and tokens it adds to the baked catalog, and the whitelist that
 * replaces the whitelist of the build. A package signed before the whitelist existed has an empty one.
 */
data class RegistryPackage(
    val sequence: Long,
    val createdAt: String,
    val networks: List<NetworkEntry>,
    val tokens: List<TokenEntry>,
    val whitelist: List<WhitelistEntry> = emptyList(),
)
