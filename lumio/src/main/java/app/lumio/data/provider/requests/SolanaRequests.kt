package app.lumio.data.provider

import app.lumio.data.net.NetworkId
import java.math.BigInteger

/** A Solana public key, 32 bytes in base58, so 32 to 44 characters. A request never carries other text into a call. */
private val SOLANA_ADDRESS = Regex("[1-9A-HJ-NP-Za-km-z]{32,44}")

/** The most transactions one history read asks for. Each one is a call of its own, so this bounds the calls of a read. */
const val MAX_SOLANA_HISTORY = 10

/**
 * The latest blockhash of [net], as the chain confirms it. A Solana transaction names one, so that it expires once the chain's block height passes
 * the blockhash's last valid height.
 */
class RecentBlockhash(net: NetworkId) : Request<SolanaBlockhash>(net, Op.BLOCKHASH)

/** A blockhash, base58, and the block height after which a transaction that names it is no longer accepted. */
data class SolanaBlockhash(val blockhash: String, val lastValidBlockHeight: Long)

/** The block height of [net] as the chain confirms it. A blockhash is no longer valid once this passes its last valid height. */
class SolanaBlockHeight(net: NetworkId) : Request<Long>(net, Op.BLOCK_HEIGHT)

/** The account at [address] as the latest answer shows it: its [SolanaAccountState], or null when the account does not exist. */
class SolanaAccountRead(net: NetworkId, val address: String) : Request<SolanaAccountState?>(net, Op.ACCOUNT_INFO) {
    init {
        require(SOLANA_ADDRESS.matches(address)) { "Not a Solana address." }
    }
}

/** An account's [owner] program, a base58 address, and its [data] in bytes. */
class SolanaAccountState(val owner: String, val data: ByteArray)

/** The lamports that an account of [size] bytes must hold to be rent-exempt, as the network asks for them now. */
class RentExemptMinimum(net: NetworkId, val size: Long) : Request<Long>(net, Op.RENT_EXEMPT) {
    init {
        require(size >= 0) { "An account size is not negative." }
    }
}

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

/** A transaction signature: 64 bytes in base58, so 86 to 88 characters. */
internal val SOLANA_SIGNATURE = Regex("[1-9A-HJ-NP-Za-km-z]{86,88}")

/**
 * The status of the transaction with [signature], as `getSignatureStatuses` reports it with the history searched, so an older signature is found too.
 * The answer is null while the network does not know the signature.
 */
class SolanaSignatureStatus(net: NetworkId, val signature: String) : Request<SolanaStatus?>(net, Op.TX) {
    init {
        require(SOLANA_SIGNATURE.matches(signature)) { "Not a Solana transaction signature." }
    }
}

/** A signature's status: [confirmed] once the network has confirmed it (processed alone is not enough), and [failed] when it ran with an error. */
data class SolanaStatus(val confirmed: Boolean, val failed: Boolean)
