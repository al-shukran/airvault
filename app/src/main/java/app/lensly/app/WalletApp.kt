package app.lensly.app

import app.lensly.app.export.BitcoinNexusKeys
import app.lensly.app.export.EthereumNexusKeys
import app.lensly.app.export.NexusKeys
import app.lensly.core.ur.Cbor

/**
 * An App of a Wallet: the software inside it that manages its accounts and signs, as the apps of a hardware wallet do. [label] is
 * the name the owner reads, and [routeId] is the App's name in a route. [nexus] gives the App's public keys for the Keystone Nexus
 * export, so every App in [entries] contributes its keys there. A new App must name its [nexus] keys here.
 */
enum class WalletApp(val label: String, val routeId: String, private val nexus: NexusKeys) {
    BITCOIN(label = "Bitcoin", routeId = "bitcoin", nexus = BitcoinNexusKeys),
    ETHEREUM(label = "Ethereum", routeId = "ethereum", nexus = EthereumNexusKeys);

    /** The public keys this App contributes to the Keystone Nexus export of a wallet whose seed is [seed]. */
    fun nexusKeys(seed: ByteArray, masterFingerprint: Int): List<Cbor> = nexus.hdKeys(seed, masterFingerprint)

    companion object {
        /** The App that [routeId] names, or null when no App has that name. */
        fun fromRouteId(routeId: String?): WalletApp? = entries.firstOrNull { it.routeId == routeId }
    }
}
