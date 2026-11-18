package app.lensly.app

import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.solana.SolanaAccount

/**
 * The accounts of every wallet in the vault, on every chain, each keyed by [addressKeyOf] its address: an Ethereum account by its address, a
 * Solana or Algorand account by the address of its public key, and a Bitcoin account by the first receive address the vault stores for it.
 * An account is named by its Display Name and label when it has them. A Solana or Algorand account has no profile, so it keeps its Auto-Name,
 * as the pairing names it. [signingWalletId] is the wallet a review is open for. Its accounts carry no wallet name, since the review names that
 * wallet already, and every other wallet's accounts name their wallet. Null names every wallet.
 */
fun internalAccountsOf(store: WalletStore, signingWalletId: String?): Map<String, InternalAccount> = buildMap {
    store.list().forEach { listed ->
        val ethereum = store.ethereumAccounts(listed.id) ?: return@forEach
        val walletName = listed.name.takeUnless { listed.id == signingWalletId }
        val base = ethereum.meta.autoNameBase
        ethereum.addresses.forEachIndexed { index, address ->
            val profile = ethereum.profiles[index]
            put(addressKeyOf(address), InternalAccount(profile?.name, autoAccountName(base, index), profile?.label, walletName))
        }
        store.solanaKeys(listed.id).forEachIndexed { index, key ->
            put(addressKeyOf(SolanaAccount.addressOf(key)), InternalAccount(null, autoAccountName(base, index), null, walletName))
        }
        store.algorandKeys(listed.id).forEachIndexed { index, key ->
            put(addressKeyOf(AlgorandAccount.addressOf(key)), InternalAccount(null, autoAccountName(base, index), null, walletName))
        }
        store.bitcoinAccounts(listed.id).forEach { record ->
            val internal = InternalAccount(record.profile.name, autoAccountName(base, record.index), record.profile.label, walletName)
            put(addressKeyOf(record.firstReceiveAddress), internal)
        }
    }
}
