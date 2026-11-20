package app.lensly.app

import app.airvault.core.model.AppId
import app.lensly.app.export.AlgorandPairingKeys
import app.lensly.app.export.BitcoinPairingKeys
import app.lensly.app.export.EthereumPairingKeys
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingKeys
import app.lensly.app.export.SolanaPairingKeys
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType

/**
 * An App of a Wallet: the software inside it that manages its accounts and signs, as the apps of a hardware wallet do. [appId] is the
 * installed chain app that serves it, as its manifest names it. [label] is the name the owner reads, and [routeId] is the App's name in a
 * route. [pairing] gives the App's accounts for the AirVault pairing.
 * [firstAccount] adds the App's account at index 0 to a new wallet, with the same derivation the App's own bulk run starts on.
 */
enum class WalletApp(
    val appId: AppId,
    val label: String,
    val routeId: String,
    private val pairing: PairingKeys,
    val firstAccount: (WalletStore, String) -> Unit,
) {
    BITCOIN(
        appId = AppId("bitcoin"),
        label = "Bitcoin",
        routeId = "bitcoin",
        pairing = BitcoinPairingKeys,
        firstAccount = { store, id -> store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, count = 1, cap = 1) },
    ),
    ETHEREUM(
        appId = AppId("evm"),
        label = "Ethereum",
        routeId = "ethereum",
        pairing = EthereumPairingKeys,
        firstAccount = { store, id -> store.addAccounts(id, count = 1, cap = 1) },
    ),
    SOLANA(
        appId = AppId("solana"),
        label = "Solana",
        routeId = "solana",
        pairing = SolanaPairingKeys,
        firstAccount = { store, id -> store.addSolanaAccounts(id, count = 1, cap = 1) },
    ),
    ALGORAND(
        appId = AppId("algorand"),
        label = "Algorand",
        routeId = "algorand",
        pairing = AlgorandPairingKeys,
        firstAccount = { store, id -> store.addAlgorandAccounts(id, count = 1, cap = 1) },
    );

    /** The accounts this App contributes to the AirVault pairing of wallet [id], whose seed is [seed] and master fingerprint is [fingerprint]. */
    fun pairedAccounts(store: WalletStore, id: String, seed: ByteArray, fingerprint: Int): List<PairingAccount> =
        pairing.paired(store, id, seed, fingerprint)

    companion object {
        /** The App that [routeId] names, or null when no App has that name. */
        fun fromRouteId(routeId: String?): WalletApp? = entries.firstOrNull { it.routeId == routeId }
    }
}
