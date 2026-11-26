package app.lumio.data.provider

import app.lumio.data.net.NetworkId

/**
 * An operation a provider serves, namespaced by the chain that owns it: `evm.call`, `btc.utxos`, `price.native` (spec 3.3.5). A namespace names one
 * chain's operation, so no provider needs a union of every chain's ops. [on] names the op of a network's own chain.
 */
@JvmInline
value class OpKey(val key: String) {
    init {
        require(KEY_FORMAT.matches(key)) { "An op key is namespace.name: $key" }
    }

    override fun toString(): String = key

    companion object {
        /** The op [name] on the chain of [net], such as `evm.nativeBalance` on an EVM network. */
        fun on(net: NetworkId, name: String): OpKey = OpKey("${net.opNamespace}.$name")
    }
}

private val KEY_FORMAT = Regex("[a-z]+\\.[a-zA-Z]+")

/** The namespace of the ops of [NetworkId]'s chain: `btc`, `evm`, `sol` or `algo`. */
private val NetworkId.opNamespace: String
    get() = when {
        isBtc -> "btc"
        isEvm -> "evm"
        isSol -> "sol"
        isAlgo -> "algo"
        else -> error("Lumio has no ops for $chain")
    }

/** The operations that Lumio's providers serve, by key. */
object Ops {
    val BTC_TIP_HEIGHT = OpKey("btc.tipHeight")
    val BTC_ADDRESS_STATS = OpKey("btc.addressStats")
    val BTC_ADDRESS_TXS = OpKey("btc.addressTxs")
    val BTC_UTXOS = OpKey("btc.utxos")
    val BTC_FEE_ESTIMATES = OpKey("btc.feeEstimates")
    val BTC_BLOCK = OpKey("btc.block")
    val BTC_TX = OpKey("btc.tx")
    val BTC_BROADCAST = OpKey("btc.broadcast")

    val EVM_NATIVE_BALANCE = OpKey("evm.nativeBalance")
    val EVM_NONCE = OpKey("evm.nonce")
    val EVM_CALL = OpKey("evm.call")
    val EVM_GAS_FEES = OpKey("evm.gasFees")
    val EVM_TX_HISTORY = OpKey("evm.txHistory")
    val EVM_BROADCAST = OpKey("evm.broadcast")
    val EVM_RECEIPT = OpKey("evm.receipt")

    val SOL_NATIVE_BALANCE = OpKey("sol.nativeBalance")
    val SOL_BLOCKHASH = OpKey("sol.blockhash")
    val SOL_BLOCK_HEIGHT = OpKey("sol.blockHeight")
    val SOL_ACCOUNT_INFO = OpKey("sol.accountInfo")
    val SOL_RENT_EXEMPT = OpKey("sol.rentExempt")
    val SOL_TX = OpKey("sol.tx")
    val SOL_TX_HISTORY = OpKey("sol.txHistory")
    val SOL_TOKEN_BALANCES = OpKey("sol.tokenBalances")
    val SOL_BROADCAST = OpKey("sol.broadcast")

    val ALGO_NATIVE_BALANCE = OpKey("algo.nativeBalance")
    val ALGO_TOKEN_BALANCES = OpKey("algo.tokenBalances")
    val ALGO_ASSET_INFO = OpKey("algo.assetInfo")
    val ALGO_SUGGESTED_PARAMS = OpKey("algo.suggestedParams")
    val ALGO_PENDING_TX = OpKey("algo.pendingTx")
    val ALGO_INDEXED_TX = OpKey("algo.indexedTx")
    val ALGO_BROADCAST = OpKey("algo.broadcast")

    val PRICE_NATIVE = OpKey("price.native")
    val PRICE_TOKEN = OpKey("price.token")
    val PRICE_CHART = OpKey("price.chart")

    val LOGO_TOKEN = OpKey("logo.token")

    val MARKET_TRENDING = OpKey("market.trending")
    val MARKET_FIAT = OpKey("market.fiat")

    /** The balance reads that DATA providers are tried before RPC providers for (router rule 5). */
    val BALANCE_READS = setOf(EVM_NATIVE_BALANCE, SOL_NATIVE_BALANCE, ALGO_NATIVE_BALANCE, SOL_TOKEN_BALANCES, ALGO_TOKEN_BALANCES)
}
