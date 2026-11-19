package app.lensly.app.sign

import app.lensly.core.ur.PathStep

/**
 * The key an Ethereum request of a Mnemonic wallet signs with, m/44'/60'/[account]'/0/[index]. Account N signs with its own key,
 * m/44'/60'/N'/0/0, which is EvmKey(N, 0). The BIP44 address key m/44'/60'/0'/0/N, which Rabby derives from the account 0 key, is
 * EvmKey(0, N), and it signs as account N too.
 */
data class EvmKey(val account: Int, val index: Int) {
    /** The account this key signs as: N for m/44'/60'/N'/0/0 and for m/44'/60'/0'/0/N. */
    val accountIndex: Int
        get() = if (index == 0) account else index

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
         * The key of [path] in a wallet of [accountCount] accounts, or null when [path] is not one of its keys. Two paths are signable,
         * m/44'/60'/N'/0/0 and m/44'/60'/0'/0/N, and N must be below [accountCount]. Any other path is no key of the wallet, and so is
         * an N past the last account.
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
