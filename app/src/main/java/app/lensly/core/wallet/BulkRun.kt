package app.lensly.core.wallet

/**
 * Derivation for a Bulk Run. Each account is the one [HdWallet.address] gives for its index, so a run and a single
 * account always agree.
 */
object BulkRun {
    /**
     * The EIP-55 addresses of the accounts at [indices] of [wallet], in index order. [onProgress] is called after each
     * account with the number derived so far. Every account is a full key derivation, so call this off the main thread.
     */
    fun addresses(wallet: HdWallet, indices: IntRange, onProgress: (derived: Int) -> Unit = {}): List<String> =
        indices.mapIndexed { position, index ->
            wallet.address(index).also { onProgress(position + 1) }
        }
}
