package app.lensly.app.export

import app.lensly.app.AccountProfile
import app.lensly.app.WalletStore
import app.lensly.app.autoAccountName
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.wallet.AccountAddresses
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
 * The Ethereum App: one key for each account, the address key m/44'/60'/N'/0/0 of account N, and one account for each of them. Each account
 * carries the name Lensly shows for it, its Auto-Name when it has none, and its label and group.
 */
object EthereumPairingKeys : PairingKeys {
    override fun paired(routeId: String, store: WalletStore, id: String, seed: ByteArray): PairingApp {
        val meta = requireNotNull(store.meta(id)) { "No wallet $id." }
        val wallet = HdWallet(seed)
        val keys = (0 until meta.accountCount).map { account ->
            PairingKey.ethereumAddress(account, AccountAddresses.addressXpub(wallet.accountXpub(account), 0))
        }
        val profiles = store.accountProfiles(id, meta.accountCount)
        val accounts = (0 until meta.accountCount).map { index ->
            namedAccount(key = index, child = null, profile = profiles[index], autoName = autoAccountName(meta.autoNameBase, index))
        }
        return PairingApp(app = routeId, keys = keys, accounts = accounts)
    }
}

/**
 * The Bitcoin App: the watch-only keys of every account index the wallet holds on each network, with the four Script Types of each index,
 * as the Keystone Nexus export has them. Each key is one account, and it carries the name, label and group the owner gave the stored
 * Bitcoin account of the same Script Type, network and index. A key with no stored account carries its Auto-Name and nothing else.
 */
object BitcoinPairingKeys : PairingKeys {
    override fun paired(routeId: String, store: WalletStore, id: String, seed: ByteArray): PairingApp {
        val meta = requireNotNull(store.meta(id)) { "No wallet $id." }
        val stored = store.bitcoinAccounts(id)
        val profiles = stored.associate { it.key to it.profile }
        val indices = stored.map { it.network to it.index }.distinct()
        val entries = indices.flatMap { (network, index) ->
            BtcWatchOnly.accounts(seed, network, accountIndex = index).map { account -> account to index }
        }
        val keys = entries.map { (account, _) -> PairingKey.bitcoinAccount(account) }
        val accounts = entries.mapIndexed { key, (account, index) ->
            val profile = profiles[Triple(account.scriptType, account.network, index)]
            namedAccount(key = key, child = null, profile = profile, autoName = autoAccountName(meta.autoNameBase, index))
        }
        return PairingApp(app = routeId, keys = keys, accounts = accounts)
    }
}

/**
 * The pairing account of one key or address index: its Display Name, or [autoName] when the owner gave it none, and its label and group,
 * each cut to the room a pairing gives it. Ethereum accounts and Bitcoin accounts are named the same way.
 */
private fun namedAccount(key: Int, child: Int?, profile: AccountProfile?, autoName: String): PairingAccount = PairingAccount(
    key = key,
    child = child,
    name = fitBytes(profile?.name ?: autoName, PairingCodec.MAX_NAME_BYTES),
    label = profile?.label?.let { fitBytes(it, PairingCodec.MAX_LABEL_BYTES) },
    group = profile?.group?.let { fitBytes(it, PairingCodec.MAX_LABEL_BYTES) },
)

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
