package app.lensly.core.registry.update

import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.registry.WalletEntry
import app.lensly.core.registry.WhitelistEntry

/**
 * The verified contents of a registry package: the networks and tokens it adds to the baked catalog, and the lists that replace the
 * build's lists when the package carries them.
 *
 * [whitelist], [denylist] and [wallets] are null when the package does not carry one, and then the build's list stays in force: a
 * package cannot clear a list by leaving it out. An empty list is one the package carries, and it clears the build's.
 */
data class RegistryPackage(
    val sequence: Long,
    val createdAt: String,
    val networks: List<NetworkEntry>,
    val tokens: List<TokenEntry>,
    val whitelist: List<WhitelistEntry>? = null,
    val denylist: List<DenylistEntry>? = null,
    val wallets: List<WalletEntry>? = null,
)
