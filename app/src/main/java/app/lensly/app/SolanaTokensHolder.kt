package app.lensly.app

import android.content.Context
import app.lensly.core.solana.SolanaTokenList

/**
 * The SPL tokens that Solana reviews name: the list bundled in the shared assets, read on the first call and kept after it. The first
 * call reads the assets, so call this off the main thread. Nothing here reaches the network.
 */
object SolanaTokensHolder {
    /** The bundled token list. It is in the shared assets beside the registry. */
    private const val BUNDLED_PATH = "registry/solana-tokens.json"

    private var tokens: SolanaTokenList? = null

    /** The bundled token list. The asset is read once, and the same list is returned after that. */
    @Synchronized
    fun get(context: Context): SolanaTokenList =
        tokens ?: SolanaTokenList.parse(context.assets.open(BUNDLED_PATH).bufferedReader().use { it.readText() })
            .also { tokens = it }
}
