package app.lensly.app

import app.lensly.app.export.BitcoinNexusKeys
import app.lensly.app.export.BitcoinPairingKeys
import app.lensly.app.export.EthereumNexusKeys
import app.lensly.app.export.EthereumPairingKeys
import app.lensly.app.export.NexusKeys
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingKeys
import app.lensly.core.ur.Cbor

/**
 * An App of a Wallet: the software inside it that manages its accounts and signs, as the apps of a hardware wallet do. [label] is
 * the name the owner reads, and [routeId] is the App's name in a route. [nexus] gives the App's public keys for the Keystone Nexus
 * export, and [pairing] its keys and accounts for the AirVault pairing, so every App in [entries] contributes to both. A new App must
 * name its [nexus] and [pairing] keys here.
 */
enum class WalletApp(
    val label: String,
    val routeId: String,
    private val nexus: NexusKeys,
    private val pairing: PairingKeys,
) {
    BITCOIN(label = "Bitcoin", routeId = "bitcoin", nexus = BitcoinNexusKeys, pairing = BitcoinPairingKeys),
    ETHEREUM(label = "Ethereum", routeId = "ethereum", nexus = EthereumNexusKeys, pairing = EthereumPairingKeys);

    /** The public keys this App contributes to the Keystone Nexus export of a wallet whose seed is [seed]. */
    fun nexusKeys(seed: ByteArray, masterFingerprint: Int): List<Cbor> = nexus.hdKeys(seed, masterFingerprint)

    /** The keys and accounts this App contributes to the AirVault pairing of wallet [id], whose seed is [seed]. */
    fun pairedApp(store: WalletStore, id: String, seed: ByteArray): PairingApp = pairing.paired(routeId, store, id, seed)

    companion object {
        /** The App that [routeId] names, or null when no App has that name. */
        fun fromRouteId(routeId: String?): WalletApp? = entries.firstOrNull { it.routeId == routeId }
    }
}
