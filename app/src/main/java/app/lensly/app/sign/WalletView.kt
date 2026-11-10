package app.lensly.app.sign

/**
 * What the sign pipeline knows about a wallet: how many accounts it has, and the address of each account. It holds no key
 * material, so it can be built from public data alone.
 *
 * A Mnemonic wallet has a master fingerprint, which its requests must name. A Single Key wallet has no master key, so
 * [masterFingerprint] is null, and its one account is the address of its key.
 */
class WalletView(
    val masterFingerprint: Int?,
    val accountCount: Int,
    val addressAt: (Int) -> String,
) {
    companion object {
        /** The view of a Single Key wallet, whose one account is the address of its key. */
        fun singleKey(address: String): WalletView =
            WalletView(masterFingerprint = null, accountCount = 1, addressAt = { address })
    }
}
