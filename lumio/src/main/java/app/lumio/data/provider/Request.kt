package app.lumio.data.provider

import app.lumio.data.net.NetworkId
import java.math.BigInteger

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

/** Broadcasts the signed transaction [raw] and returns its hash as the endpoint reports it. */
class Broadcast(net: NetworkId, val raw: ByteArray) : Request<String>(net, Op.BROADCAST)
