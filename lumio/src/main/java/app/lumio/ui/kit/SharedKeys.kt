package app.lumio.ui.kit

/**
 * The keys of the shared elements (section 3.3). An element that moves from one screen to the next has the same key on both, so the move is drawn
 * from one place to the other. The keys carry the wallet, chain and account ids, so each key is unique on the screen.
 */
object SharedKeys {
    fun walletOrb(walletId: String): String = "wallet-orb-$walletId"
    fun walletName(walletId: String): String = "wallet-name-$walletId"
    fun walletTotal(walletId: String): String = "wallet-total-$walletId"
    fun accountIcon(walletId: String, chain: String, index: Int): String = "acct-icon-$walletId-$chain-$index"
    fun accountBalance(walletId: String, chain: String, index: Int): String = "acct-balance-$walletId-$chain-$index"
    fun assetIcon(assetKey: String): String = "asset-icon-$assetKey"
    fun assetAmount(assetKey: String): String = "asset-amount-$assetKey"

    /** The amount field of a send, which moves to the review sentence. */
    const val SEND_AMOUNT = "send-amount"
}
