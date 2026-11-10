package app.lensly.app.sign

/**
 * What the sign pipeline knows about a wallet: its master fingerprint, how many accounts it has, and the address of
 * each account. It holds no key material, so it can be built from public data alone.
 */
class WalletView(
    val masterFingerprint: Int,
    val accountCount: Int,
    val addressAt: (Int) -> String,
)
