package app.lensly.core.registry

import java.math.BigInteger

/** The Route of a swap: its tokens in trade order, and the fee of each pool between them. A V2 route has no fees. */
internal class Route(val tokens: List<String>, val fees: List<Int>) {
    fun reversed(): Route = Route(tokens.reversed(), fees.reversed())
}

private const val ADDRESS_BYTES_LENGTH = 20
private const val FEE_BYTES = 3
private const val V3_POOL_BYTES = FEE_BYTES + ADDRESS_BYTES_LENGTH
private const val V3_ONE_POOL_BYTES = ADDRESS_BYTES_LENGTH + V3_POOL_BYTES

/** The route of a swap, as "via Uniswap v3 (0.05%)" for one pool, and each pool of several, in trade order. */
internal fun via(call: StepCall, protocol: String, route: Route): String {
    val hops = route.tokens.zipWithNext()
    if (hops.size == 1) {
        return if (route.fees.isEmpty()) "via $protocol" else "via $protocol (${feeText(route.fees[0])})"
    }
    val path = if (route.fees.isEmpty()) {
        route.tokens.joinToString(" → ") { call.symbolOf(it) }
    } else {
        hops.indices.joinToString(", ") { index ->
            "${call.symbolOf(hops[index].first)} → ${call.symbolOf(hops[index].second)} (${feeText(route.fees[index])})"
        }
    }
    return "via $protocol: $path"
}

/** A Uniswap v3 fee in hundredths of a bip, as a percentage: 500 is 0.05%, and 10000 is 1%. */
internal fun feeText(fee: Int): String = Amounts.format(BigInteger.valueOf(fee.toLong()), 4) + "%"

/**
 * The route of a Uniswap v3 path: a token, then for each pool a 3-byte fee and the next 20-byte token. Null unless the path is one
 * pool or more, in whole pools, as the router reads it.
 */
internal fun v3Route(path: ByteArray): Route? {
    if (path.size < V3_ONE_POOL_BYTES || (path.size - ADDRESS_BYTES_LENGTH) % V3_POOL_BYTES != 0) return null
    val tokens = arrayListOf(path.addressAt(0))
    val fees = ArrayList<Int>()
    var at = ADDRESS_BYTES_LENGTH
    while (at < path.size) {
        fees += ((path[at].toInt() and 0xff) shl 16) or ((path[at + 1].toInt() and 0xff) shl 8) or (path[at + 2].toInt() and 0xff)
        tokens += path.addressAt(at + FEE_BYTES)
        at += V3_POOL_BYTES
    }
    return Route(tokens, fees)
}
