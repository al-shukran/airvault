package app.lensly.core.registry

import java.math.BigInteger

/**
 * Steps for command 0x10 (V4_SWAP) on Universal Router V2. Its input is ABI-encoded as (bytes actions, bytes[] params): the router runs
 * actions[i] on params[i], in order, and the two arrays have the same length. Each action is one step. The router runs nine actions, and
 * Lensly reads those nine. A command is read whole or not at all. An action Lensly does not read, params that do not decode, a list or
 * path past its bound, or a pool that cannot exist returns null, and the caller shows the command as raw input with a warning. No partial
 * summary is given, so the steps are never shown for part of a command.
 *
 * Action IDs are src/libraries/Actions.sol at Uniswap/v4-periphery 4d85e047e321d0c02134fec9044879c0cd00ea7d, which
 * Uniswap/universal-router b122a8d2a5 pins as lib/v4-periphery. Argument layouts are the structs of src/interfaces/IV4Router.sol and
 * src/libraries/PathKey.sol. The meaning of each action, and which address pays or receives, is src/V4Router.sol with
 * src/base/BaseActionsRouter.sol and src/base/DeltaResolver.sol. The router's dispatch is V4Router.sol's `_handleAction`, which reverts
 * with UnsupportedAction on SETTLE_PAIR, TAKE_PAIR, SWEEP, WRAP, UNWRAP and the rest, and UR's V4SwapRouter.sol adds none. The pool rules
 * are Uniswap/v4-core b619b6718e at src/libraries/LPFeeLibrary.sol (MAX_LP_FEE, which also excludes the dynamic-fee flag) and
 * src/PoolManager.sol (currency0 below currency1 in initialize). A take portion is at most BPS_DENOMINATOR (10,000) in
 * src/libraries/BipsLibrary.sol at v4-periphery 4d85e047, and calculatePortion reverts above it.
 */
internal object V4SwapSteps {
    private const val V4 = "Uniswap v4"

    /** The most actions one command may hold. This is a Lensly bound: a longer list is shown as raw input. */
    private const val MAX_ACTIONS = 16

    /** The most pools one path may hold. This is a Lensly bound: a longer path is shown as raw input. */
    private const val MAX_HOPS = 4

    private const val SELECTOR_BYTES = 4
    private const val NO_HOOK = "0x0000000000000000000000000000000000000000"
    private const val MSG_SENDER = "0x0000000000000000000000000000000000000001"
    private const val ADDRESS_THIS = "0x0000000000000000000000000000000000000002"

    /** LPFeeLibrary.MAX_LP_FEE, in hundredths of a bip: 1,000,000 is 100%. */
    private val MAX_LP_FEE: BigInteger = BigInteger.valueOf(1_000_000)

    /** BipsLibrary.BPS_DENOMINATOR: a take portion above the whole credit is refused by the router. */
    private val BPS_DENOMINATOR: BigInteger = BigInteger.valueOf(10_000)

    /** ActionConstants.CONTRACT_BALANCE: a settle amount that means the whole balance the router holds. */
    private val CONTRACT_BALANCE: BigInteger = BigInteger.ONE.shiftLeft(255)

    /** The largest uint256. No amount can exceed a maximum this large, so it is shown as no limit. */
    private val MAX_UINT256: BigInteger = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)

    /** The argument of a single-pool swap, which is the one struct `abi.decode(params, (ExactInputSingleParams))` reads. */
    private const val SINGLE = "((address,address,uint24,int24,address),bool,uint128,uint128,bytes)"

    /** The argument of a multi-pool swap, which is the one struct `abi.decode(params, (ExactInputParams))` reads. */
    private const val PATH = "(address,(address,uint24,int24,address,bytes)[],uint128,uint128)"

    private val INPUT = params("actions" to "bytes", "params" to "bytes[]")

    /** One action: the layout its params decode with, and the steps it reads from them, or null when they cannot be read. */
    private class Action(val layout: List<WhitelistParam>, val describe: (StepCall, List<AbiValue>) -> CallSteps?)

    private val ACTIONS: Map<Int, Action> = mapOf(
        0x06 to Action(params("params" to SINGLE)) { c, v -> singlePool(c, v, exactIn = true) },
        0x07 to Action(params("params" to PATH)) { c, v -> path(c, v, exactIn = true) },
        0x08 to Action(params("params" to SINGLE)) { c, v -> singlePool(c, v, exactIn = false) },
        0x09 to Action(params("params" to PATH)) { c, v -> path(c, v, exactIn = false) },
        0x0b to Action(params("currency" to "address", "amount" to "uint256", "payerIsUser" to "bool")) { c, v -> settle(c, v) },
        0x0c to Action(params("currency" to "address", "maxAmount" to "uint256")) { c, v -> settleAll(c, v) },
        0x0e to Action(params("currency" to "address", "recipient" to "address", "amount" to "uint256")) { c, v -> take(c, v) },
        0x0f to Action(params("currency" to "address", "minAmount" to "uint256")) { c, v -> takeAll(c, v) },
        0x10 to Action(params("currency" to "address", "recipient" to "address", "bips" to "uint256")) { c, v -> takePortion(c, v) },
    )

    /** The steps of a V4_SWAP [input], one per action, or null when the input or any of its actions cannot be read. */
    fun describe(input: ByteArray, call: StepCall): CallSteps? {
        val values = read(input, INPUT) ?: return null
        val actions = values[0].bytes()
        val params = values[1].items()
        if (actions.size > MAX_ACTIONS || actions.size != params.size) return null
        if (actions.isEmpty()) return CallSteps(listOf("No actions: the router does nothing"), emptyList())
        val lines = ArrayList<String>()
        val warnings = ArrayList<String>()
        actions.indices.forEach { index ->
            val steps = action(actions[index].toInt() and 0xff, params[index].bytes(), call) ?: return null
            lines += steps.lines
            warnings += steps.warnings
        }
        return CallSteps(lines, warnings)
    }

    private fun action(id: Int, input: ByteArray, call: StepCall): CallSteps? {
        val known = ACTIONS[id] ?: return null
        val values = read(input, known.layout) ?: return null
        return known.describe(call, values)
    }

    /** The values of [input] read with [layout] as calldata with no selector, or null when they do not decode. */
    private fun read(input: ByteArray, layout: List<WhitelistParam>): List<AbiValue>? {
        val calldata = ByteArray(SELECTOR_BYTES) + input
        return (AbiDecoder.decode(calldata, layout) as? CallDecode.Ok)?.params?.map { it.value }
    }

    /**
     * A swap through one pool, whose struct is (poolKey, zeroForOne, amount, limit, hookData). The pool key must be ordered as the pool
     * manager orders it, and its fee must be a static one, so a pool that cannot exist is not read.
     */
    private fun singlePool(c: StepCall, v: List<AbiValue>, exactIn: Boolean): CallSteps? {
        val s = v[0].items()
        val key = s[0].items()
        val currency0 = key[0].address()
        val currency1 = key[1].address()
        val fee = key[2].word()
        if (currency0 >= currency1 || fee > MAX_LP_FEE) return null
        val (tokenIn, tokenOut) = if (s[1].word() == BigInteger.ONE) currency0 to currency1 else currency1 to currency0
        val route = Route(listOf(tokenIn, tokenOut), listOf(fee.toInt()))
        return trade(c, route, listOf(key[4].address()), s[2].word(), s[3].word(), exactIn)
    }

    /**
     * A swap through a path of pools, whose struct is (currency, path, amount, limit). An exact input starts at its currency, and each
     * pool is the hop to its intermediate currency. An exact output ends at its currency, and the path lists the input of each pool first.
     */
    private fun path(c: StepCall, v: List<AbiValue>, exactIn: Boolean): CallSteps? {
        val s = v[0].items()
        val hops = s[1].items().map { it.items() }
        if (hops.isEmpty() || hops.size > MAX_HOPS || hops.any { it[1].word() > MAX_LP_FEE }) return null
        val intermediates = hops.map { it[0].address() }
        val tokens = if (exactIn) listOf(s[0].address()) + intermediates else intermediates + s[0].address()
        val route = Route(tokens, hops.map { it[1].word().toInt() })
        return trade(c, route, hops.map { it[3].address() }, s[2].word(), s[3].word(), exactIn)
    }

    /**
     * One swap as the signer reads it: an exact input spends its amount for at least its minimum, and an exact output buys its amount for
     * at most its maximum. A zero amount is the router's whole credit for an exact input, and its whole debt for an exact output.
     */
    private fun trade(c: StepCall, route: Route, hooks: List<String>, amount: BigInteger, limit: BigInteger, exactIn: Boolean): CallSteps {
        val tokenIn = route.tokens.first()
        val tokenOut = route.tokens.last()
        val sentence = if (exactIn) {
            val spend = if (amount.signum() == 0) {
                "all ${c.symbolOf(tokenIn)} owed to the router"
            } else {
                "exactly ${c.amount(amount, tokenIn)}"
            }
            "Swap $spend for ≥ ${c.amount(limit, tokenOut)}"
        } else {
            val buy = if (amount.signum() == 0) "all ${c.symbolOf(tokenOut)} the router owes" else "exactly ${c.amount(amount, tokenOut)}"
            "Swap up to ${c.amount(limit, tokenIn)} for $buy"
        }
        return CallSteps(listOf("$sentence ${viaV4(c, route)}"), hookWarnings(hooks))
    }

    /** The route as the Uniswap v3 steps name one: "via Uniswap v4 (0.3%)" for one pool, and each pool in trade order for more. */
    private fun viaV4(c: StepCall, route: Route): String {
        val hops = route.tokens.zipWithNext()
        if (hops.size == 1) return "via $V4 (${feeText(route.fees[0])})"
        val pools = hops.mapIndexed { index, (from, to) -> "${c.symbolOf(from)} → ${c.symbolOf(to)} (${feeText(route.fees[index])})" }
        return "via $V4: " + pools.joinToString(", ")
    }

    /** One warning per distinct hook. A hook runs its own code in its pool, so the amounts of the swap can differ from those shown. */
    private fun hookWarnings(hooks: List<String>): List<String> = hooks.filter { it != NO_HOOK }.distinct().map { hook ->
        "A pool hook at ${shortAddress(hook)} runs its own code during this swap, so the amounts can differ from those shown."
    }

    /**
     * Pays a stated amount, or the router's whole debt when zero, to the pool manager. The payer is the signer or the router, and a native
     * coin is paid from the router's own balance, so it names no payer.
     */
    private fun settle(c: StepCall, v: List<AbiValue>): CallSteps {
        val currency = v[0].address()
        val amount = v[1].word()
        val what = when (amount) {
            CONTRACT_BALANCE -> "all ${c.symbolOf(currency)} the router holds"
            BigInteger.ZERO -> "all ${c.symbolOf(currency)} the router owes"
            else -> c.amount(amount, currency)
        }
        val payer = when {
            currency == NATIVE_COIN -> ""
            v[2].word() == BigInteger.ONE -> " from you"
            else -> " from the router"
        }
        return CallSteps(listOf("Pay $what$payer to $V4"), emptyList())
    }

    /**
     * Pays the whole debt of [currency], which must not exceed the stated maximum. The signer pays an ERC-20, and a native coin comes
     * from the router.
     */
    private fun settleAll(c: StepCall, v: List<AbiValue>): CallSteps {
        val currency = v[0].address()
        val maximum = v[1].word()
        val limit = if (maximum == MAX_UINT256) " (no limit)" else " (at most ${c.amount(maximum, currency)})"
        val payer = if (currency == NATIVE_COIN) "" else " from you"
        return CallSteps(listOf("Pay all ${c.symbolOf(currency)} the router owes$payer to $V4$limit"), emptyList())
    }

    /** Takes a stated amount, or the router's whole credit when zero, from the pool manager to [recipient]. */
    private fun take(c: StepCall, v: List<AbiValue>): CallSteps {
        val currency = v[0].address()
        val recipient = recipientName(c, v[1].address())
        val amount = v[2].word()
        val what = if (amount.signum() == 0) "all ${c.symbolOf(currency)} owed to the router" else c.amount(amount, currency)
        return CallSteps(listOf("Take $what from $V4 to $recipient"), emptyList())
    }

    /** Takes the whole credit of [currency] to the signer, which must be at least the stated minimum. */
    private fun takeAll(c: StepCall, v: List<AbiValue>): CallSteps {
        val currency = v[0].address()
        val minimum = v[1].word()
        val least = if (minimum.signum() == 0) "" else " (at least ${c.amount(minimum, currency)})"
        return CallSteps(listOf("Take all ${c.symbolOf(currency)} owed to the router from $V4 to you$least"), emptyList())
    }

    /** Takes a share, in basis points, of the whole credit of [currency] to [recipient]. A share above the whole is not read. */
    private fun takePortion(c: StepCall, v: List<AbiValue>): CallSteps? {
        val currency = v[0].address()
        val recipient = recipientName(c, v[1].address())
        val bips = v[2].word()
        if (bips > BPS_DENOMINATOR) return null
        val share = percentOf(bips)
        return CallSteps(listOf("Take $share of all ${c.symbolOf(currency)} owed to the router from $V4 to $recipient"), emptyList())
    }

    /** The recipient a take names: the signer for the flag 0x…01, the router for 0x…02, and the address otherwise. */
    private fun recipientName(c: StepCall, address: String): String = when (address) {
        MSG_SENDER -> "you"
        ADDRESS_THIS -> "the router"
        else -> c.who(address)
    }

    /** The named fields of a call, in the order its layout lists them. */
    private fun params(vararg fields: Pair<String, String>): List<WhitelistParam> =
        fields.map { (name, type) -> WhitelistParam(name, type) }
}
