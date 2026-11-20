package app.lensly.core.wallet

import app.airvault.chains.evm.EvmAccountPath
import app.lensly.core.ur.PathStep

/**
 * The key an Ethereum path of a Mnemonic wallet names, m/44'/60'/[account]'/0/[index]. The wallet's keys are of two forms, for its accounts 0
 * until its account count:
 * - the account key m/44'/60'/N'/0/0, which is EvmKey(N, 0). Account N signs with its own key, as Ledger Live derives it;
 * - the BIP44 address key m/44'/60'/0'/0/N, which is EvmKey(0, N). Rabby derives its address N from the account 0 key. It is the key of no
 *   account, so it has no [accountIndex].
 * m/44'/60'/0'/0/0 is the account 0 key, EvmKey(0, 0).
 */
data class EvmKey(val account: Int, val index: Int) {
    /** The account whose own key this is: N for m/44'/60'/N'/0/0, or null for a BIP44 address key, which belongs to no account. */
    val accountIndex: Int?
        get() = if (index == 0) account else null

    /** The path of this key as the review shows it, such as m/44'/60'/0'/0/3. */
    val path: String
        get() = "m/44'/60'/$account'/0/$index"

    companion object {
        /** The one key of a Single Key wallet. A Single Key has no path, so this is only its place in the request. */
        val SINGLE_KEY = EvmKey(0, 0)

        /**
         * The key of [path] in a wallet of [accountCount] accounts, or null when [path] is not one of its keys. Two forms are the wallet's keys,
         * m/44'/60'/N'/0/0 and m/44'/60'/0'/0/N, and N must be below [accountCount]. Any other path is no key of the wallet, and so is an N past
         * the last account.
         */
        fun of(path: List<PathStep>, accountCount: Int): EvmKey? {
            EvmAccountPath.accountOf(path)?.let { account -> return EvmKey(account, 0).takeIf { account < accountCount } }
            EvmAccountPath.addressOf(path)?.let { index -> return EvmKey(0, index).takeIf { index < accountCount } }
            return null
        }
    }
}
