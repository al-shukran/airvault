package app.lumio.data.provider

import app.lumio.data.net.NetworkId
import java.math.BigInteger

/**
 * A typed read or write. The type parameter is the result a provider returns. This slice names only the requests it routes. Each later slice
 * adds the requests of the provider that first serves them.
 */
sealed class Request<T>(val net: NetworkId, val op: Op)

/** The height of the chain tip. */
class TipHeight(net: NetworkId) : Request<Long>(net, Op.TIP_HEIGHT)

/** The native coin balance of [address], in the chain's base unit. */
class NativeBalance(net: NetworkId, val address: String) : Request<BigInteger>(net, Op.NATIVE_BALANCE)

/** Broadcasts the signed transaction [raw] and returns its id. */
class Broadcast(net: NetworkId, val raw: ByteArray) : Request<String>(net, Op.BROADCAST)
