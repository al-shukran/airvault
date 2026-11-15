package app.lensly.app.export

import app.lensly.app.WalletStore
import app.lensly.app.autoAccountName
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.wallet.HdWallet

/**
 * What one App of a wallet contributes to the AirVault pairing (see [PairingCodec]): its watch-only keys and its accounts, with the names
 * Lensly shows for them. Public data only. [paired] reads the wallet's seed, which the caller zeroes once the pairing is built.
 */
interface PairingKeys {
    /** The keys and accounts of the App named [routeId] in wallet [id]. */
    fun paired(routeId: String, store: WalletStore, id: String, seed: ByteArray): PairingApp
}

/**
 * The Ethereum App: the account key m/44'/60'/0', and one account for each address index the wallet holds. Each account carries the name
 * Lensly shows for it, its Auto-Name when it has none, and its label and group.
 */
object EthereumPairingKeys : PairingKeys {
    override fun paired(routeId: String, store: WalletStore, id: String, seed: ByteArray): PairingApp {
        val meta = requireNotNull(store.meta(id)) { "No wallet $id." }
        val key = PairingKey.ethereumAccount(HdWallet(seed).accountXpub())
        val profiles = store.accountProfiles(id, meta.accountCount)
        val accounts = (0 until meta.accountCount).map { index ->
            val profile = profiles[index]
            PairingAccount(
                key = 0,
                child = index,
                name = fitBytes(profile?.name ?: autoAccountName(meta.autoNameBase, index), PairingCodec.MAX_NAME_BYTES),
                label = profile?.label?.let { fitBytes(it, PairingCodec.MAX_LABEL_BYTES) },
                group = profile?.group?.let { fitBytes(it, PairingCodec.MAX_LABEL_BYTES) },
            )
        }
        return PairingApp(app = routeId, keys = listOf(key), accounts = accounts)
    }
}

/**
 * The Bitcoin App: the watch-only keys of every account index the wallet holds on each network, with the four Script Types of each index,
 * as the Keystone Nexus export has them. Lensly gives a Bitcoin account no name, label or group yet, so the accounts carry none.
 */
object BitcoinPairingKeys : PairingKeys {
    override fun paired(routeId: String, store: WalletStore, id: String, seed: ByteArray): PairingApp {
        val indices = store.bitcoinAccounts(id).map { it.network to it.index }.distinct()
        val keys = indices.flatMap { (network, index) -> BtcWatchOnly.accounts(seed, network, accountIndex = index) }
            .map { PairingKey.bitcoinAccount(it) }
        val accounts = keys.indices.map { index -> PairingAccount(key = index, child = null, name = null, label = null, group = null) }
        return PairingApp(app = routeId, keys = keys, accounts = accounts)
    }
}

/**
 * [text] cut to at most [maxBytes] UTF-8 bytes, at a character boundary. Every name Lensly stores is within the limit, so only a name of
 * 4-byte characters near the limit is cut.
 */
internal fun fitBytes(text: String, maxBytes: Int): String {
    var end = text.length
    while (text.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) {
        end = text.offsetByCodePoints(end, -1)
    }
    return text.substring(0, end)
}
