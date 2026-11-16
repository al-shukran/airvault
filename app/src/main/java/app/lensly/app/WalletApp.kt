package app.lensly.app

import app.lensly.app.export.BitcoinPairingKeys
import app.lensly.app.export.EthereumPairingKeys
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingKeys
import app.lensly.app.export.SolanaPairingKeys

/**
 * An App of a Wallet: the software inside it that manages its accounts and signs, as the apps of a hardware wallet do. [label] is
 * the name the owner reads, and [routeId] is the App's name in a route. [pairing] gives the App's keys and accounts for the AirVault
 * pairing. An App whose [pairing] is null is not in the pairing yet, so the pairing carries exactly the Apps it carried before that App was listed.
 */
enum class WalletApp(
    val label: String,
    val routeId: String,
    private val pairing: PairingKeys?,
) {
    BITCOIN(label = "Bitcoin", routeId = "bitcoin", pairing = BitcoinPairingKeys),
    ETHEREUM(label = "Ethereum", routeId = "ethereum", pairing = EthereumPairingKeys),
    SOLANA(label = "Solana", routeId = "solana", pairing = SolanaPairingKeys),
    ALGORAND(label = "Algorand", routeId = "algorand", pairing = null);

    /** The keys and accounts this App contributes to the AirVault pairing of wallet [id], whose seed is [seed]. Null when the pairing has no App. */
    fun pairedApp(store: WalletStore, id: String, seed: ByteArray): PairingApp? = pairing?.paired(routeId, store, id, seed)

    companion object {
        /** The App that [routeId] names, or null when no App has that name. */
        fun fromRouteId(routeId: String?): WalletApp? = entries.firstOrNull { it.routeId == routeId }
    }
}
