package app.lensly.core.registry

import app.lensly.core.wallet.toHex
import java.math.BigInteger

/**
 * Steps for the calls on the Uniswap SwapRouter02 deployment on Ethereum mainnet: the Uniswap v3 swaps, unwrapWETH9 to a recipient,
 * refundETH, and multicall, which runs its calls in order. The layouts and semantics are those of
 * contracts/interfaces/IV3SwapRouter.sol, contracts/base/MulticallExtended.sol and contracts/base/PeripheryPaymentsExtended.sol at
 * Uniswap/swap-router-contracts 70bc2e40, and of contracts/base/Multicall.sol and contracts/base/PeripheryPayments.sol at
 * Uniswap/v3-periphery 24ccf014. An exact input of zero spends the whole balance the router holds, as CONTRACT_BALANCE does in
 * V3SwapRouter.sol. A single-pool swap with a price limit is not read, because the limit can stop the swap short of its amount.
 *
 * A multicall reverts the whole batch when one of its calls fails. A multicall inside a multicall is not read, since no real transaction has
 * one. A call Lensly cannot read is shown as raw input and warns. The recipient flags are read as in V3SwapRouter.sol: address(1) is the
 * signer, and address(2) is the router.
 */
internal object SwapRouterSteps {
    private const val ROUTER = "0x68b3465833fb72a70ecdf485e0e4c7bd8665fc45"
    private const val MAINNET = 1L
    private const val SELECTOR_BYTES = 4
    private const val UINT24_BITS = 24
    private const val MULTICALL = "0xac9650d8"
    private const val MULTICALL_DEADLINE = "0x5ae401dc"
    private const val MULTICALL_SIGNATURE = "multicall(bytes[])"
    private const val MULTICALL_DEADLINE_SIGNATURE = "multicall(uint256,bytes[])"
    private const val SINGLE = "(address,address,uint24,address,uint256,uint256,uint160)"
    private const val PATH = "(bytes,address,uint256,uint256)"
    private const val V3 = "Uniswap v3"
    private const val MSG_SENDER = "0x0000000000000000000000000000000000000001"
    private const val ADDRESS_THIS = "0x0000000000000000000000000000000000000002"

    /** A call the router runs: its signature, the layout of its arguments, and the steps it reads from them. */
    private class Call(val signature: String, val layout: List<WhitelistParam>, val describe: (StepCall) -> CallSteps?)

    private val CALLS: Map<String, Call> = mapOf(
        "0x04e45aaf" to Call("exactInputSingle($SINGLE)", params("params" to SINGLE)) { singlePool(it, exactIn = true) },
        "0xb858183f" to Call("exactInput($PATH)", params("params" to PATH)) { multiHop(it, exactIn = true) },
        "0x5023b4df" to Call("exactOutputSingle($SINGLE)", params("params" to SINGLE)) { singlePool(it, exactIn = false) },
        "0x09b81346" to Call("exactOutput($PATH)", params("params" to PATH)) { multiHop(it, exactIn = false) },
        "0x49404b7c" to Call("unwrapWETH9(uint256,address)", params("amountMinimum" to "uint256", "recipient" to "address")) {
            unwrapWeth(it, it.uint(0), it.address(1))
        },
        "0x12210e8a" to Call("refundETH()", emptyList()) { refund(it) },
    )

    /** The steps of [call] when it is an approved SwapRouter02 call on mainnet, and null for any other call. */
    fun describe(call: StepCall, selector: String, signature: String): CallSteps? {
        if (call.chainId != MAINNET || call.contract != ROUTER) return null
        return when (selector) {
            MULTICALL -> if (signature == MULTICALL_SIGNATURE) multicall(call, call.items(0)) else null
            MULTICALL_DEADLINE -> if (signature == MULTICALL_DEADLINE_SIGNATURE) multicall(call, call.items(1)) else null
            else -> CALLS[selector]?.takeIf { it.signature == signature }?.let { known ->
                known.describe(call) ?: CallSteps(emptyList(), listOf(UNREADABLE_CALL))
            }
        }
    }

    /** The steps of a multicall's [items], which are its calls in order. */
    private fun multicall(call: StepCall, items: List<AbiValue>): CallSteps {
        if (items.isEmpty()) return CallSteps(listOf("No calls: the router does nothing"), emptyList())
        val lines = ArrayList<String>()
        val warnings = ArrayList<String>()
        items.forEach { item ->
            val steps = callSteps(call, item.bytes())
            lines += steps.lines
            warnings += steps.warnings
        }
        return CallSteps(lines, warnings)
    }

    /** The steps of one call inside a multicall: its selector names it, and its arguments are read with the layout it names. */
    private fun callSteps(call: StepCall, input: ByteArray): CallSteps {
        if (input.size < SELECTOR_BYTES) return unreadable("0x", input)
        val selector = "0x" + input.copyOf(SELECTOR_BYTES).toHex()
        // A multicall inside a multicall has no real transaction behind it, so it is shown as raw input.
        if (selector == MULTICALL || selector == MULTICALL_DEADLINE) return unreadable(selector, input)
        val known = CALLS[selector] ?: return unknown(selector, input)
        val decoded = decode(input, known.layout) ?: return unreadable(selector, input)
        val inner = StepCall(call.registry, call.network, call.contract, call.from, BigInteger.ZERO, decoded)
        return known.describe(inner) ?: unreadable(selector, input)
    }

    /** The arguments of [input], read with [layout] (which the input's selector precedes), or null when they do not decode. */
    private fun decode(input: ByteArray, layout: List<WhitelistParam>): List<DecodedParam>? =
        (AbiDecoder.decode(input, layout) as? CallDecode.Ok)?.params

    private fun unknown(selector: String, input: ByteArray): CallSteps = CallSteps(
        listOf("Unknown router call $selector: raw input 0x${input.toHex()}"),
        listOf("Lensly cannot read router call $selector in this multicall. Its raw input is under Details."),
    )

    private fun unreadable(selector: String, input: ByteArray): CallSteps = CallSteps(
        listOf("Router call $selector could not be read: raw input 0x${input.toHex()}"),
        listOf("Lensly cannot read the input of router call $selector. Its raw input is under Details."),
    )

    /** A swap through one pool: the struct is (tokenIn, tokenOut, fee, recipient, amount, limit, sqrtPriceLimitX96). */
    private fun singlePool(c: StepCall, exactIn: Boolean): CallSteps? {
        val p = c.items(0)
        if (p[6].word().signum() != 0) return null
        // A pool fee is a uint24, so a larger value is not a fee the pool can have.
        val fee = p[2].word()
        if (fee.bitLength() > UINT24_BITS) return null
        val route = Route(listOf(p[0].address(), p[1].address()), listOf(fee.toInt()))
        return swap(c, route, p[3].address(), p[4].word(), p[5].word(), exactIn)
    }

    /** A swap through a path of pools: the struct is (path, recipient, amount, limit). An exact-output path lists its output first. */
    private fun multiHop(c: StepCall, exactIn: Boolean): CallSteps? {
        val p = c.items(0)
        val route = v3Route(p[0].bytes()) ?: return null
        val trade = if (exactIn) route else route.reversed()
        return swap(c, trade, p[1].address(), p[2].word(), p[3].word(), exactIn)
    }

    /**
     * One swap, as the signer reads it: an exact input is spent for at least its minimum, and an exact output is bought for at most its
     * maximum. An exact input of zero spends the balance the router holds.
     */
    private fun swap(c: StepCall, route: Route, recipient: String, amount: BigInteger, limit: BigInteger, exactIn: Boolean): CallSteps {
        val tokenIn = route.tokens.first()
        val tokenOut = route.tokens.last()
        val sentence = if (exactIn) {
            val spend = if (amount.signum() == 0) "all ${c.symbolOf(tokenIn)} the router holds" else "exactly ${c.amount(amount, tokenIn)}"
            "Swap $spend for ≥ ${c.amount(limit, tokenOut)}"
        } else {
            "Swap up to ${c.amount(limit, tokenIn)} for exactly ${c.amount(amount, tokenOut)}"
        }
        val to = when (recipient) {
            MSG_SENDER -> ""
            ADDRESS_THIS -> ", kept in the router"
            else -> if (recipient == c.from) "" else ", sent to ${shortAddress(recipient)}"
        }
        return CallSteps(listOf("$sentence ${via(c, V3, route)}$to"), emptyList())
    }

    /** Unwraps all the WETH the router holds, if at least the minimum, and sends the ETH to [recipient]. */
    private fun unwrapWeth(c: StepCall, minimum: BigInteger, recipient: String): CallSteps {
        val least = if (minimum.signum() == 0) "" else " (at least ${c.wrappedAmount(minimum)})"
        return CallSteps(
            listOf("Unwrap all ${c.wrappedSymbol} the router holds → ${c.network.nativeSymbol} for ${c.who(recipient)}$least"),
            emptyList(),
        )
    }

    /** Sends all the native coin the router holds to the signer. */
    private fun refund(c: StepCall): CallSteps =
        CallSteps(listOf("Refund all ${c.network.nativeSymbol} the router holds to you"), emptyList())

    /** The named fields of a call, in the order its signature lists them. */
    private fun params(vararg fields: Pair<String, String>): List<WhitelistParam> =
        fields.map { (name, type) -> WhitelistParam(name, type) }
}
