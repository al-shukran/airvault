package app.lensly.app.sign

/**
 * What the sign pipeline knows about a wallet: how many accounts it has, the Display Name of each account that has one,
 * and the address of each account. It holds no key material, so it can be built from public data alone.
 *
 * A Mnemonic wallet has a master fingerprint, which its requests must name. A Single Key wallet has no master key, so
 * [masterFingerprint] is null, and its one account is the address of its key.
 */
class WalletView(
    val masterFingerprint: Int?,
    val accountCount: Int,
    /** The Display Name of each account that has one, by account index. An account missing here keeps its default label. */
    val accountNames: Map<Int, String> = emptyMap(),
    val addressAt: (Int) -> String,
) {
    companion object {
        /**
         * The view of a Single Key wallet, whose one account is the address of its key. [accountName] is its Display Name,
         * or null while the account keeps its default label.
         */
        fun singleKey(address: String, accountName: String? = null): WalletView = WalletView(
            masterFingerprint = null,
            accountCount = 1,
            accountNames = if (accountName == null) emptyMap() else mapOf(0 to accountName),
            addressAt = { address },
        )
    }
}
