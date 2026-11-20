package app.lensly.core.wallet

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

        private val COIN_PATH = listOf(PathStep(44, hardened = true), PathStep(60, hardened = true))
        private val EXTERNAL_CHAIN = PathStep(0, hardened = false)
        private const val PATH_LENGTH = 5

        /**
         * The key of [path] in a wallet of [accountCount] accounts, or null when [path] is not one of its keys. Two forms are the wallet's keys,
         * m/44'/60'/N'/0/0 and m/44'/60'/0'/0/N, and N must be below [accountCount]. Any other path is no key of the wallet, and so is an N past
         * the last account.
         */
        fun of(path: List<PathStep>, accountCount: Int): EvmKey? {
            if (path.size != PATH_LENGTH || path.subList(0, COIN_PATH.size) != COIN_PATH) return null
            val account = path[COIN_PATH.size]
            val chain = path[COIN_PATH.size + 1]
            val index = path[COIN_PATH.size + 2]
            if (!account.hardened || chain != EXTERNAL_CHAIN || index.hardened) return null
            return when {
                index.index == 0 && account.index < accountCount -> EvmKey(account.index, 0)
                account.index == 0 && index.index < accountCount -> EvmKey(0, index.index)
                else -> null
            }
        }
    }
}
