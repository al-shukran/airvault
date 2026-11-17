package app.lensly.app.export

import app.lensly.app.AccountProfile
import app.lensly.app.WalletStore
import app.lensly.app.autoAccountName
import app.lensly.core.btc.BtcAccount
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet

/**
 * What one App of a wallet contributes to the AirVault pairing (see [PairingCodec]): its keys and accounts, with the names Lensly shows for
 * them. Public data only. [paired] reads the wallet's seed, which the caller zeroes once the pairing is built.
 */
interface PairingKeys {
    /** The keys and accounts of the App named [routeId] in wallet [id]. */
    fun paired(routeId: String, store: WalletStore, id: String, seed: ByteArray): PairingApp
}

/**
 * The Ethereum App: one account for each account index, with the address of the account, m/44'/60'/N'/0/0 for account N. The pairing carries
 * the address and not the key, because Lumio shows the address and derives nothing else from the key. Each account carries the name Lensly
 * shows for it, its Auto-Name when it has none, and its label and group.
 */
object EthereumPairingKeys : PairingKeys {
    override fun paired(routeId: String, store: WalletStore, id: String, seed: ByteArray): PairingApp {
        val meta = requireNotNull(store.meta(id)) { "No wallet $id." }
        val wallet = HdWallet(seed)
        val keys = (0 until meta.accountCount).map { account ->
            PairingKey.ethereumAddress(account, AccountAddresses.addressOfKey(AccountAddresses.addressXpub(wallet.accountXpub(account), 0)))
        }
        val profiles = store.accountProfiles(id, meta.accountCount)
        val accounts = (0 until meta.accountCount).map { index ->
            namedAccount(key = index, profile = profiles[index], autoName = autoAccountName(meta.autoNameBase, index))
        }
        return PairingApp(app = routeId, keys = keys, accounts = accounts)
    }
}

/**
 * The Bitcoin App: one key for each Bitcoin account the wallet stores, and nothing else. A stored account is its network, its Script Type and its
 * index, so the pairing carries the key of that Script Type and index, and no other Script Type of the same index. Each key carries the name,
 * label and group the owner gave the stored account, or its Auto-Name when it has none.
 */
object BitcoinPairingKeys : PairingKeys {
    override fun paired(routeId: String, store: WalletStore, id: String, seed: ByteArray): PairingApp {
        val meta = requireNotNull(store.meta(id)) { "No wallet $id." }
        val stored = store.bitcoinAccounts(id).sortedWith(compareBy({ it.scriptType.purpose }, { it.network.coinType }, { it.index }))
        val keys = stored.map { record ->
            PairingKey.bitcoinAccount(BtcAccount.fromSeed(seed, record.network, record.scriptType, record.index).watchOnly())
        }
        val accounts = stored.mapIndexed { key, record ->
            namedAccount(key = key, profile = record.profile, autoName = autoAccountName(meta.autoNameBase, record.index))
        }
        return PairingApp(app = routeId, keys = keys, accounts = accounts)
    }
}

/**
 * The Solana App: one Ed25519 key for each Solana account, m/44'/501'/N', and one account for each, named as Lensly shows it. The public keys
 * are the ones the wallet stores, and each is the address, so the pairing carries the 32 bytes of the key and no chain code.
 */
object SolanaPairingKeys : PairingKeys {
    override fun paired(routeId: String, store: WalletStore, id: String, seed: ByteArray): PairingApp {
        val meta = requireNotNull(store.meta(id)) { "No wallet $id." }
        val publicKeys = store.solanaKeys(id)
        val keys = publicKeys.mapIndexed { index, publicKey -> PairingKey.solanaAccount(index, publicKey) }
        val accounts = publicKeys.indices.map { index ->
            namedAccount(key = index, profile = null, autoName = autoAccountName(meta.autoNameBase, index))
        }
        return PairingApp(app = routeId, keys = keys, accounts = accounts)
    }
}

/**
 * The Algorand App: one Ed25519 key for each Algorand account, m/44'/283'/N'/0/0, and one account for each, named as Lensly shows it. The public
 * keys are the ones the wallet stores, and each is the key the address is made of, so the pairing carries the 32 bytes and no chain code.
 */
object AlgorandPairingKeys : PairingKeys {
    override fun paired(routeId: String, store: WalletStore, id: String, seed: ByteArray): PairingApp {
        val meta = requireNotNull(store.meta(id)) { "No wallet $id." }
        val publicKeys = store.algorandKeys(id)
        val keys = publicKeys.mapIndexed { index, publicKey -> PairingKey.algorandAccount(index, publicKey) }
        val accounts = publicKeys.indices.map { index ->
            namedAccount(key = index, profile = null, autoName = autoAccountName(meta.autoNameBase, index))
        }
        return PairingApp(app = routeId, keys = keys, accounts = accounts)
    }
}

/**
 * The pairing account of one key or address index: its Display Name, or [autoName] when the owner gave it none, and its label and group,
 * each cut to the room a pairing gives it. Ethereum accounts and Bitcoin accounts are named the same way.
 */
private fun namedAccount(key: Int, profile: AccountProfile?, autoName: String): PairingAccount = PairingAccount(
    key = key,
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
