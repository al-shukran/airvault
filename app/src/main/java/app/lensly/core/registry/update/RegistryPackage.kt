package app.lensly.core.registry.update

import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry

/** The verified contents of a registry package: the networks and tokens it adds to the baked catalog. */
data class RegistryPackage(
    val sequence: Long,
    val createdAt: String,
    val networks: List<NetworkEntry>,
    val tokens: List<TokenEntry>,
)
