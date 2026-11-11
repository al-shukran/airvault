package app.lensly.app.sign

import app.lensly.app.VisualIdentity
import app.lensly.app.generatedIdentity

/**
 * What the sign pipeline knows about a wallet: its record [id], its name and Visual Identity, how many accounts it has, the
 * Display Name of each account that has one, and the address and Visual Identity of each account. It holds no key material,
 * so it can be built from public data alone.
 *
 * A Mnemonic wallet has a master fingerprint, which its requests must name. A Single Key wallet has no master key, so
 * [masterFingerprint] is null, and its one account is the address of its key.
 */
class WalletView(
    /** The wallet's record id. A review made from this view names it, so the key is read from the same wallet. */
    val id: String,
    val masterFingerprint: Int?,
    val accountCount: Int,
    /** The wallet's name at creation. An account without a Display Name is labelled from it, as in "Seed Wallet 0". */
    val autoNameBase: String,
    /** The Display Name of each account that has one, by account index. An account missing here keeps its Auto-Name. */
    val accountNames: Map<Int, String> = emptyMap(),
    val addressAt: (Int) -> String,
    /** The wallet's name as the owner sees it. The review header shows it. */
    val name: String = autoNameBase,
    /** The wallet's Visual Identity, shown beside its name. */
    val identity: VisualIdentity = generatedIdentity(0),
    /** The Visual Identity of account [index], shown beside its name. */
    val accountIdentity: (Int) -> VisualIdentity = ::generatedIdentity,
) {
    companion object {
        /**
         * The view of a Single Key wallet, whose one account is the address of its key. [accountName] is its Display Name,
         * or null while the account keeps its Auto-Name from [autoNameBase].
         */
        fun singleKey(
            id: String,
            address: String,
            autoNameBase: String,
            accountName: String? = null,
            name: String = autoNameBase,
            identity: VisualIdentity = generatedIdentity(0),
            accountIdentity: VisualIdentity = generatedIdentity(0),
        ): WalletView = WalletView(
            id = id,
            masterFingerprint = null,
            accountCount = 1,
            autoNameBase = autoNameBase,
            accountNames = if (accountName == null) emptyMap() else mapOf(0 to accountName),
            addressAt = { address },
            name = name,
            identity = identity,
            accountIdentity = { accountIdentity },
        )
    }
}
