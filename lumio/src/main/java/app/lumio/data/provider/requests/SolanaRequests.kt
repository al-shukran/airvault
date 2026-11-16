package app.lumio.data.provider

import app.lumio.data.net.NetworkId
import java.math.BigInteger

/** A Solana public key, 32 bytes in base58, so 32 to 44 characters. A request never carries other text into a call. */
private val SOLANA_ADDRESS = Regex("[1-9A-HJ-NP-Za-km-z]{32,44}")

/** The most transactions one history read asks for. Each one is a call of its own, so this bounds the calls of a read. */
const val MAX_SOLANA_HISTORY = 10

/** The latest blockhash of [net]. A Solana transaction names one, so that it expires once the chain has moved on. */
class RecentBlockhash(net: NetworkId) : Request<String>(net, Op.BLOCKHASH)

/**
 * The most recent transactions that involve a Solana [address], newest first, up to [limit] of them. Each row carries the change in the address's
 * balance, and that change is null when the answer does not show the balance.
 */
class SolanaAddressHistory(net: NetworkId, val address: String, val limit: Int) : Request<List<SolanaTx>>(net, Op.TX_HISTORY) {
    init {
        require(SOLANA_ADDRESS.matches(address)) { "Not a Solana address." }
        require(limit in 1..MAX_SOLANA_HISTORY) { "A history holds 1 to $MAX_SOLANA_HISTORY transactions." }
    }
}

/** One transaction of a Solana history: [blockTimeS] in epoch seconds, [lamportDelta] the address's balance change in lamports. */
data class SolanaTx(val signature: String, val slot: Long, val blockTimeS: Long?, val failed: Boolean, val lamportDelta: Long?)

/** The SPL token balances of [owner] under the classic token program. Each [SplBalance] is one token account. */
class SplTokenBalances(net: NetworkId, val owner: String) : Request<List<SplBalance>>(net, Op.TOKEN_BALANCES) {
    init {
        require(SOLANA_ADDRESS.matches(owner)) { "Not a Solana address." }
    }
}

/** One SPL token account: the [mint] of its token, its [amount] in base units, and the token's [decimals]. */
data class SplBalance(val mint: String, val amount: BigInteger, val decimals: Int)
