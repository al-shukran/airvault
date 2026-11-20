package app.lensly.app

import app.lensly.app.export.AlgorandPairingKeys
import app.lensly.app.export.BitcoinPairingKeys
import app.lensly.app.export.EthereumPairingKeys
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingKeys
import app.lensly.app.export.SolanaPairingKeys

/**
 * An App of a Wallet: the software inside it that manages its accounts and signs, as the apps of a hardware wallet do. [label] is
 * the name the owner reads, and [routeId] is the App's name in a route. [pairing] gives the App's accounts for the AirVault pairing.
 */
enum class WalletApp(
    val label: String,
    val routeId: String,
    private val pairing: PairingKeys,
) {
    BITCOIN(label = "Bitcoin", routeId = "bitcoin", pairing = BitcoinPairingKeys),
    ETHEREUM(label = "Ethereum", routeId = "ethereum", pairing = EthereumPairingKeys),
    SOLANA(label = "Solana", routeId = "solana", pairing = SolanaPairingKeys),
    ALGORAND(label = "Algorand", routeId = "algorand", pairing = AlgorandPairingKeys);

    /** The accounts this App contributes to the AirVault pairing of wallet [id], whose seed is [seed] and master fingerprint is [fingerprint]. */
    fun pairedAccounts(store: WalletStore, id: String, seed: ByteArray, fingerprint: Int): List<PairingAccount> =
        pairing.paired(store, id, seed, fingerprint)

    companion object {
        /** The App that [routeId] names, or null when no App has that name. */
        fun fromRouteId(routeId: String?): WalletApp? = entries.firstOrNull { it.routeId == routeId }
    }
}
