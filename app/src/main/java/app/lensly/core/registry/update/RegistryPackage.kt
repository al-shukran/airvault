package app.lensly.core.registry.update

import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.registry.WalletEntry
import app.lensly.core.registry.WhitelistEntry

/**
 * The verified contents of a registry package: the networks and tokens it adds to the baked catalog, and the whitelist that
 * replaces the whitelist of the build. A package signed before the whitelist existed has an empty one.
 *
 * [denylist] and [wallets] replace the build's lists when the package carries them. A null list is one the package does not carry,
 * so the build's list stays in force: a package signed before the denylist existed cannot clear it.
 */
data class RegistryPackage(
    val sequence: Long,
    val createdAt: String,
    val networks: List<NetworkEntry>,
    val tokens: List<TokenEntry>,
    val whitelist: List<WhitelistEntry> = emptyList(),
    val denylist: List<DenylistEntry>? = null,
    val wallets: List<WalletEntry>? = null,
)
