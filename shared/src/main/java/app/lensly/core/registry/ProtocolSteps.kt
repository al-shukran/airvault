package app.lensly.core.registry

import java.math.BigInteger

/**
 * Steps for the calls the signed whitelist approves on Aave V3 pools, Compound V3 markets, the Lido staking contract and the wrapped
 * native coins. A call is described only when its network, contract and selector are one of the entries here, and its signature is the
 * one the entry approved. Addresses and selectors are the candidate entries in tools/registry-data/approved-contracts. Each Compound
 * market's name and base token were read on its network with `symbol()` and `baseToken()`.
 *
 * Aave's interest rate mode 2 is variable and 1 is stable, and any other mode is shown with a warning, since the pool reverts on it. An
 * amount of 2^256 - 1 is the whole balance for a withdrawal or a repayment, and it is shown as "all".
 */
internal object ProtocolSteps {
    /** A call the approval names: its signature, and the steps it reads. */
    private class Known(val signature: String, val describe: (StepCall) -> CallSteps)

    /** A Compound V3 market: its name, and the base token it lends. An asset that is not the base token is collateral. */
    private class Market(val symbol: String, val base: String)

    private const val AAVE_V3 = "Aave V3"
    private const val NO_ADDRESS = "0x0000000000000000000000000000000000000000"

    private val AAVE_POOLS: List<Pair<Long, String>> = listOf(
        1L to "0x87870bca3f3fd6335c3f4ce8392d69350b4fa4e2",
        10L to "0x794a61358d6845594f94dc1db02a252b5b4814ad",
        56L to "0x6807dc923806fe8fd134338eabca509979a7e0cb",
        137L to "0x794a61358d6845594f94dc1db02a252b5b4814ad",
        8453L to "0xa238dd80c259a72e81d7e4664a9801593f98d1c5",
        42161L to "0x794a61358d6845594f94dc1db02a252b5b4814ad",
        43114L to "0x794a61358d6845594f94dc1db02a252b5b4814ad",
    )

    private val COMPOUND_MARKETS: Map<Pair<Long, String>, Market> = mapOf(
        (1L to "0xc3d688b66703497daa19211eedff47f25384cdc3") to Market("cUSDCv3", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"),
        (1L to "0xa17581a9e3356d9a858b789d68b4d866e593ae94") to Market("cWETHv3", "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2"),
        (10L to "0x2e44e174f7d53f0212823acc11c01a11d58c5bcb") to Market("cUSDCv3", "0x0b2c639c533813f4aa9d7837caf62653d097ff85"),
        (137L to "0xf25212e676d1f7f89cd72ffee66158f541246445") to Market("cUSDCv3", "0x2791bca1f2de4661ed88a30c99a7a9449aa84174"),
        (8453L to "0xb125e6687d4313864e53df431d5425969c15eb2f") to Market("cUSDCv3", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"),
        (42161L to "0x9c4ec768c28520b50860ea7a15bd7213a9ff58bf") to Market("cUSDCv3", "0xaf88d065e77c8cc2239327c5edb3a432268e5831"),
    )

    /** The wrapped native coin on each network: WETH, WBNB or WAVAX, with the same decimals as the native coin. */
    private val WRAPPED_NATIVE: List<Pair<Long, String>> = listOf(
        1L to "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
        10L to "0x4200000000000000000000000000000000000006",
        56L to "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c",
        8453L to "0x4200000000000000000000000000000000000006",
        42161L to "0x82af49447d8a07e3bd95bd0d56f35241523fbab1",
        43114L to "0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7",
    )

    private const val LIDO_CONTRACT = "0xae7ab96520de3a18e5e111b5eaab095312d7fe84"

    private val AAVE: Map<String, Known> = mapOf(
        "0x617ba037" to Known("supply(address,uint256,address,uint16)") { c ->
            val line = "Supply ${c.amount(c.uint(1), c.address(0))} to $AAVE_V3 on behalf of ${c.who(c.address(2))}${referral(c.uint(3))}"
            CallSteps(listOf(line), emptyList())
        },
        "0x69328dec" to Known("withdraw(address,uint256,address)") { c ->
            val line = "Withdraw ${amountOrAll(c, c.uint(1), c.address(0))} from $AAVE_V3 to ${c.who(c.address(2))}"
            CallSteps(listOf(line), emptyList())
        },
        "0xa415bcad" to Known("borrow(address,uint256,uint256,uint16,address)") { c ->
            val mode = c.uint(2)
            val line = "Borrow ${c.amount(c.uint(1), c.address(0))} from $AAVE_V3 at ${rateText(mode)} " +
                "on behalf of ${c.who(c.address(4))}${referral(c.uint(3))}"
            CallSteps(listOf(line), rateWarnings(mode))
        },
        "0x573ade81" to Known("repay(address,uint256,uint256,address)") { c ->
            val mode = c.uint(2)
            val line = "Repay ${amountOrAll(c, c.uint(1), c.address(0))} to $AAVE_V3 at ${rateText(mode)} " +
                "on behalf of ${c.who(c.address(3))}"
            CallSteps(listOf(line), rateWarnings(mode))
        },
        "0x5a3b74b9" to Known("setUserUseReserveAsCollateral(address,bool)") { c ->
            val symbol = c.symbolOf(c.address(0))
            val line = if (c.bool(1)) "Use $symbol as collateral on $AAVE_V3" else "Stop using $symbol as collateral on $AAVE_V3"
            CallSteps(listOf(line), emptyList())
        },
    )

    private fun compound(market: Market): Map<String, Known> = mapOf(
        "0xf2b9fdb8" to Known("supply(address,uint256)") { c ->
            val asset = c.address(0)
            val collateral = if (asset == market.base) "" else " as collateral"
            CallSteps(listOf("Supply ${c.amount(c.uint(1), asset)}$collateral to Compound V3 (${market.symbol})"), emptyList())
        },
        "0xf3fef3a3" to Known("withdraw(address,uint256)") { c ->
            val asset = c.address(0)
            val collateral = if (asset == market.base) "" else " collateral"
            val what = amountOrAll(c, c.uint(1), asset)
            CallSteps(listOf("Withdraw $what$collateral from Compound V3 (${market.symbol})"), emptyList())
        },
    )

    private val LIDO: Map<String, Known> = mapOf(
        "0xa1903eab" to Known("submit(address)") { c ->
            val referral = c.address(0)
            val note = if (referral == NO_ADDRESS) "" else " (referral ${shortAddress(referral)})"
            CallSteps(listOf("Stake ${c.nativeAmount(c.sent)} with Lido for stETH$note"), emptyList())
        },
    )

    private val WRAPPED: Map<String, Known> = mapOf(
        "0xd0e30db0" to Known("deposit()") { c ->
            CallSteps(listOf("Wrap ${c.nativeAmount(c.sent)} → ${c.wrappedSymbol}"), emptyList())
        },
        "0x2e1a7d4d" to Known("withdraw(uint256)") { c ->
            CallSteps(listOf("Unwrap ${c.wrappedAmount(c.uint(0))} → ${c.network.nativeSymbol}"), emptyList())
        },
    )

    /** Every approved call, by network and contract, then by selector. Built after the tables above, which it reads. */
    private val BY_CONTRACT: Map<Pair<Long, String>, Map<String, Known>> = buildMap {
        AAVE_POOLS.forEach { put(it, AAVE) }
        COMPOUND_MARKETS.forEach { (key, market) -> put(key, compound(market)) }
        WRAPPED_NATIVE.forEach { put(it, WRAPPED) }
        put(1L to LIDO_CONTRACT, LIDO)
    }

    /** The steps of [call] when it is an approved Aave, Compound, Lido or wrapped-coin call, and null for any other call. */
    fun describe(call: StepCall, selector: String, signature: String): CallSteps? {
        val known = BY_CONTRACT[call.chainId to call.contract]?.get(selector) ?: return null
        if (known.signature != signature) return null
        return known.describe(call)
    }

    /** An amount of the whole balance reads as "all" of the token, and any other as its quantity. */
    private fun amountOrAll(c: StepCall, amount: BigInteger, token: String): String =
        if (amount == ContractCall.MAX_UINT256) "all ${c.symbolOf(token)}" else c.amount(amount, token)

    private fun referral(code: BigInteger): String = if (code.signum() == 0) "" else " (referral code $code)"

    private fun rateText(mode: BigInteger): String = when (mode) {
        BigInteger.TWO -> "a variable rate"
        BigInteger.ONE -> "a stable rate"
        else -> "interest rate mode $mode"
    }

    private fun rateWarnings(mode: BigInteger): List<String> =
        if (mode == BigInteger.ONE || mode == BigInteger.TWO) {
            emptyList()
        } else {
            listOf("Lensly does not know Aave interest rate mode $mode, which the pool reverts")
        }
}
