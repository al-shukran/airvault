package app.lumio.data.provider

import app.lumio.btc.EsploraAddressStats
import app.lumio.btc.EsploraBlock
import app.lumio.btc.EsploraTx
import app.lumio.btc.EsploraTxStatus
import app.lumio.data.net.NetworkId
import java.math.BigInteger
import kotlinx.serialization.Serializable

/** A Bitcoin address: letters and digits only, so a request can never carry a path separator into a URL. */
private val BTC_ADDRESS = Regex("[A-Za-z0-9]{14,90}")

/** A block hash or a transaction id: 32 bytes in lowercase hex. */
private val BTC_HASH = Regex("[0-9a-f]{64}")

/** An EVM address: 0x and 40 hex digits. */
private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

/** Hex data of any length, as a JSON-RPC call sends it: 0x and an even number of hex digits. */
private val HEX_DATA = Regex("0x([0-9a-fA-F]{2})*")

/** A token contract as [app.lumio.data.asset.AssetId] keeps it: lowercase. */
private val TOKEN_CONTRACT = Regex("0x[0-9a-f]{40}")

/**
 * A typed read or write. The type parameter is the result a provider returns. The router matches each request's [op] against the operations
 * a provider supports. The EVM requests map to JSON-RPC calls in `app.lumio.data.rpc.EvmCalls`.
 */
sealed class Request<T>(val net: NetworkId, val op: Op)

/** The height of the chain tip. */
class TipHeight(net: NetworkId) : Request<Long>(net, Op.TIP_HEIGHT)

/** The native coin balance of [address] in the chain's base unit, at the latest block. */
class NativeBalance(net: NetworkId, val address: String) : Request<BigInteger>(net, Op.NATIVE_BALANCE)

/** The transaction count of [address]: the nonce of the next transaction when [pending] is true, else the count at the latest block. */
class Nonce(net: NetworkId, val address: String, val pending: Boolean) : Request<Long>(net, Op.NONCE)

/** The gas that a transfer of [valueWei] from [from] to [to] uses, as the endpoint estimates it. */
class EstimateGas(net: NetworkId, val from: String, val to: String, val valueWei: BigInteger) : Request<BigInteger>(net, Op.RPC_CALL)

/** The base fee of the next block and the priority fee that the endpoint suggests. */
class GasFees(net: NetworkId) : Request<GasQuote>(net, Op.FEE_ESTIMATES)

/** A fee quote, both in wei per gas: the next block's base fee and the suggested priority fee. */
data class GasQuote(val baseFee: BigInteger, val priorityFee: BigInteger)

/**
 * The USD price of the native coin of [net]. Only a network with a price coin is priced: see `app.lumio.data.price.NativeCoins`. A testnet has none.
 */
class NativePrice(net: NetworkId) : Request<PriceQuote>(net, Op.PRICE)

/**
 * A USD price for one coin: [usd] per coin, the change over the last 24 hours in percent ([change24hPct] is null when the source gives none), and
 * [asOfMs], the time the source gives for the price, in epoch milliseconds. It is stored in the cache, so it is serializable.
 */
@Serializable
data class PriceQuote(val usd: Double, val change24hPct: Double?, val asOfMs: Long)

/**
 * The USD price of the ERC-20 token at [contract] on [net], lowercase. Only a network with a price prefix is priced: see
 * `app.lumio.data.price.DefiLlamaChains`. The price is asked for with the token's contract, never with an owner's address.
 */
class TokenPrice(net: NetworkId, val contract: String) : Request<PriceQuote>(net, Op.TOKEN_PRICE) {
    init {
        require(TOKEN_CONTRACT.matches(contract)) { "A token contract is 0x and 40 lowercase hex digits." }
    }
}

/**
 * A read-only contract call on an EVM network: [data] is the call's encoded input (a selector and its arguments) sent to [to], at the latest
 * block. The answer is the call's return data as hex text, `0x` when the address holds no code. Lumio uses it for token balances and token
 * metadata, and the arguments it sends are public: an owner's address, a token contract, and nothing that is secret.
 */
class ContractCall(net: NetworkId, val to: String, val data: String) : Request<String>(net, Op.RPC_CALL) {
    init {
        require(EVM_ADDRESS.matches(to)) { "Not an EVM address." }
        require(HEX_DATA.matches(data)) { "The call data is not hex." }
    }
}

/** Broadcasts the signed transaction [raw] and returns its hash as the endpoint reports it. */
class Broadcast(net: NetworkId, val raw: ByteArray) : Request<String>(net, Op.BROADCAST)

/** The confirmed and mempool history of a Bitcoin [address], as an Esplora server reports it. */
class AddressStats(net: NetworkId, val address: String) : Request<EsploraAddressStats>(net, Op.ADDRESS_STATS) {
    init {
        require(BTC_ADDRESS.matches(address)) { "Not a Bitcoin address." }
    }
}

/** The most recent transactions that involve a Bitcoin [address]. */
class AddressTxs(net: NetworkId, val address: String) : Request<List<EsploraTx>>(net, Op.ADDRESS_TXS) {
    init {
        require(BTC_ADDRESS.matches(address)) { "Not a Bitcoin address." }
    }
}

/** The fee rate in sat/vB for each confirmation target, in blocks. */
class FeeEstimates(net: NetworkId) : Request<Map<Int, Double>>(net, Op.FEE_ESTIMATES)

/** The block with [hash]. A block never changes, so its answer is cached for good. */
class Block(net: NetworkId, val hash: String) : Request<EsploraBlock>(net, Op.BLOCK) {
    init {
        require(BTC_HASH.matches(hash)) { "Not a block hash." }
    }
}

/** The status of the transaction [txid]. */
class Tx(net: NetworkId, val txid: String) : Request<EsploraTxStatus>(net, Op.TX) {
    init {
        require(BTC_HASH.matches(txid)) { "Not a transaction id." }
    }
}

/** The raw transaction [txid] in hex, as an Esplora server serves it. A send reads the transactions it spends this way. */
class RawTx(net: NetworkId, val txid: String) : Request<String>(net, Op.TX) {
    init {
        require(BTC_HASH.matches(txid)) { "Not a transaction id." }
    }
}
