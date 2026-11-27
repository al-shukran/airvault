package app.lensly.core.registry.update

import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.SolanaFees
import app.lensly.core.registry.TokenEntry
import app.lensly.core.registry.WalletEntry
import app.lensly.core.registry.WhitelistEntry

/**
 * The verified contents of a registry package: the networks and tokens it adds to the baked catalog or corrects in it, and the lists
 * that replace the build's lists when the package carries them.
 *
 * [whitelist], [denylist] and [wallets] are null when the package does not carry one, and then the list in effect stays in force
 * (see [RegistryUpdate.withInstalled]): a package cannot clear a list by leaving it out. An empty list is one the package carries,
 * and it clears the build's. [solanaFees] is null the same way, and the build's constants stay. [revokedKeys] are the IDs of the
 * signing keys the package revokes (see [RegistryUpdate.install]); only a package signed by a [KeyRole.RECOVERY] key carries any.
 * [signerKeyId] is the ID of the key that signed it.
 */
data class RegistryPackage(
    val sequence: Long,
    val createdAt: String,
    val networks: List<NetworkEntry>,
    val tokens: List<TokenEntry>,
    val whitelist: List<WhitelistEntry>? = null,
    val denylist: List<DenylistEntry>? = null,
    val wallets: List<WalletEntry>? = null,
    val solanaFees: SolanaFees? = null,
    val revokedKeys: List<String> = emptyList(),
    val signerKeyId: String,
)
