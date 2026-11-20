package app.airvault.chains.evm

import app.lensly.core.ur.PathStep

/**
 * The key paths an Ethereum wallet holds, on [EVM_COIN_TYPE]: the account key m/44'/60'/N'/0/0 of account N, and the BIP44 address key
 * m/44'/60'/0'/0/N, which Rabby derives from the account 0 key. [accountOf] and [addressOf] read the number a path names, or return null for
 * any other path, so the wallet and the signer read one rule.
 */
object EvmAccountPath {
    private const val PURPOSE = 44
    private const val PATH_LENGTH = 5
    private val COIN_TYPE_STEPS = listOf(PathStep(PURPOSE, hardened = true), PathStep(EVM_COIN_TYPE, hardened = true))
    private val EXTERNAL_CHAIN = PathStep(0, hardened = false)

    /** N of m/44'/60'/N'/0/0 when [steps] is an account key, or null for any other path. */
    fun accountOf(steps: List<PathStep>): Int? {
        if (steps.size != PATH_LENGTH || steps.subList(0, COIN_TYPE_STEPS.size) != COIN_TYPE_STEPS) return null
        val account = steps[2]
        return account.index.takeIf { account.hardened && steps[3] == EXTERNAL_CHAIN && steps[4] == PathStep(0, hardened = false) }
    }

    /** N of m/44'/60'/0'/0/N when [steps] is a BIP44 address key, or null for any other path. */
    fun addressOf(steps: List<PathStep>): Int? {
        if (steps.size != PATH_LENGTH || steps.subList(0, COIN_TYPE_STEPS.size) != COIN_TYPE_STEPS) return null
        val address = steps[4]
        return address.index.takeIf { steps[2] == PathStep(0, hardened = true) && steps[3] == EXTERNAL_CHAIN && !address.hardened }
    }
}
