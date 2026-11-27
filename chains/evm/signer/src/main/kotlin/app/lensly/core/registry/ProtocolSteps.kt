package app.lensly.core.registry

import app.airvault.chains.evm.signer.ContractCall
import java.math.BigInteger

/**
 * Steps for the calls the signed whitelist approves on Aave V3 pools, Compound V3 markets, the Lido contracts and the wrapped native
 * coins. A call is described only when its network, contract and selector are one of the entries here, and its signature is the one the
 * entry approved. Addresses and selectors are the candidate entries in tools/registry-data/approved-contracts. Each Compound market's name
 * and base token were read on its network with `symbol()` and `baseToken()`.
 *
 * Aave's interest rate mode 2 is variable and 1 is stable, and any other mode is shown with a warning, since the pool reverts on it. An
 * amount of 2^256 - 1 is the whole balance for a withdrawal or a repayment, and it is shown as "all". The permit variants of the Aave pool,
 * the Aave WETH gateway, the Compound calls that move a balance to or from another account, and the Lido withdrawal queue are approved on
 * Ethereum only.
 */
internal object ProtocolSteps {
    /** A call the approval names: its signature, and the steps it reads. */
    private class Known(val signature: String, val describe: (StepCall) -> CallSteps)

    /** A Compound V3 market: its name, and the base token it lends. An asset that is not the base token is collateral. */
    internal class CompoundMarket(val symbol: String, val base: String)

    private const val AAVE_V3 = "Aave V3"
    private const val AAVE_POOL_MAINNET = "0x87870bca3f3fd6335c3f4ce8392d69350b4fa4e2"
    private const val AAVE_WETH_GATEWAY = "0xd01607c3c5ecaba394d8be377a08590149325722"
    private const val SUPPLIES_TO = "supplies to"
    private const val WITHDRAWS_TO = "withdraws to"
    private const val REPAYS_DEBT_OF = "repays the debt of"
    private const val NO_ADDRESS = "0x0000000000000000000000000000000000000000"

    /** Aave's variable interest rate mode. `BigInteger.TWO` needs a newer API level than the module's minimum, so it is built here. */
    private val VARIABLE_RATE: BigInteger = BigInteger.valueOf(2)
    private const val EMODE_WARNING = "Efficiency mode changes the limits and liquidation thresholds of your Aave V3 positions"

    private val AAVE_POOLS: List<Pair<Long, String>> = listOf(
        1L to "0x87870bca3f3fd6335c3f4ce8392d69350b4fa4e2",
        10L to "0x794a61358d6845594f94dc1db02a252b5b4814ad",
        56L to "0x6807dc923806fe8fd134338eabca509979a7e0cb",
        137L to "0x794a61358d6845594f94dc1db02a252b5b4814ad",
        8453L to "0xa238dd80c259a72e81d7e4664a9801593f98d1c5",
        42161L to "0x794a61358d6845594f94dc1db02a252b5b4814ad",
        43114L to "0x794a61358d6845594f94dc1db02a252b5b4814ad",
    )

    private val COMPOUND_MARKETS: Map<Pair<Long, String>, CompoundMarket> = mapOf(
        (1L to "0xc3d688b66703497daa19211eedff47f25384cdc3") to CompoundMarket("cUSDCv3", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"),
        (1L to "0xa17581a9e3356d9a858b789d68b4d866e593ae94") to CompoundMarket("cWETHv3", "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2"),
        (10L to "0x2e44e174f7d53f0212823acc11c01a11d58c5bcb") to CompoundMarket("cUSDCv3", "0x0b2c639c533813f4aa9d7837caf62653d097ff85"),
        (137L to "0xf25212e676d1f7f89cd72ffee66158f541246445") to CompoundMarket("cUSDCv3", "0x2791bca1f2de4661ed88a30c99a7a9449aa84174"),
        (8453L to "0xb125e6687d4313864e53df431d5425969c15eb2f") to CompoundMarket("cUSDCv3", "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"),
        (42161L to "0x9c4ec768c28520b50860ea7a15bd7213a9ff58bf") to CompoundMarket("cUSDCv3", "0xaf88d065e77c8cc2239327c5edb3a432268e5831"),
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
    private const val LIDO_WSTETH = "0x7f39c581f595b53c5cb19bd0b3f8da6c935e2ca0"
    private const val LIDO_QUEUE = "0x889edc2edab5f40e902b864ad4d7ade8e412f9b1"

    private val AAVE: Map<String, Known> = mapOf(
        "0x617ba037" to Known("supply(address,uint256,address,uint16)") { c ->
            val line = "Supply ${c.amount(c.uint(1), c.address(0))} to $AAVE_V3 on behalf of ${c.who(c.address(2))}${referral(c.uint(3))}"
            CallSteps(listOf(line), notYou(c.from, SUPPLIES_TO, c.address(2)))
        },
        "0x69328dec" to Known("withdraw(address,uint256,address)") { c ->
            val line = "Withdraw ${amountOrAll(c, c.uint(1), c.address(0))} from $AAVE_V3 to ${c.who(c.address(2))}"
            CallSteps(listOf(line), notYou(c.from, WITHDRAWS_TO, c.address(2)))
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
            CallSteps(listOf(line), rateWarnings(mode) + notYou(c.from, REPAYS_DEBT_OF, c.address(3)))
        },
        "0x5a3b74b9" to Known("setUserUseReserveAsCollateral(address,bool)") { c ->
            val symbol = c.symbolOf(c.address(0))
            val line = if (c.bool(1)) "Use $symbol as collateral on $AAVE_V3" else "Stop using $symbol as collateral on $AAVE_V3"
            CallSteps(listOf(line), emptyList())
        },
        "0x28530a47" to Known("setUserEMode(uint8)") { c ->
            val category = c.uint(0)
            val line = if (category.signum() == 0) {
                "Turn off efficiency mode on $AAVE_V3"
            } else {
                "Set efficiency mode on $AAVE_V3 to category $category"
            }
            CallSteps(listOf(line), listOf(EMODE_WARNING))
        },
    )

    /** The permit variants of the pool's actions: the same action, approved by a signature in the call instead of an earlier approval. */
    private val AAVE_PERMIT: Map<String, Known> = mapOf(
        "0x02c205f0" to Known("supplyWithPermit(address,uint256,address,uint16,uint256,uint8,bytes32,bytes32)") { c ->
            val line = "Supply ${c.amount(c.uint(1), c.address(0))} to $AAVE_V3 on behalf of ${c.who(c.address(2))}" +
                "${referral(c.uint(3))}, approved by a signed permit"
            CallSteps(listOf(line), notYou(c.from, SUPPLIES_TO, c.address(2)))
        },
        "0xee3e210b" to Known("repayWithPermit(address,uint256,uint256,address,uint256,uint8,bytes32,bytes32)") { c ->
            val mode = c.uint(2)
            val line = "Repay ${amountOrAll(c, c.uint(1), c.address(0))} to $AAVE_V3 at ${rateText(mode)} " +
                "on behalf of ${c.who(c.address(3))}, approved by a signed permit"
            CallSteps(listOf(line), rateWarnings(mode) + notYou(c.from, REPAYS_DEBT_OF, c.address(3)))
        },
        "0x2dad97d4" to Known("repayWithATokens(address,uint256,uint256)") { c ->
            val mode = c.uint(2)
            val token = c.address(0)
            val line = "Repay ${amountOrAll(c, c.uint(1), token)} to $AAVE_V3 at ${rateText(mode)}, paid from your supplied ${c.symbolOf(token)}"
            CallSteps(listOf(line), rateWarnings(mode))
        },
    )

    /**
     * Aave's WETH gateway on Ethereum, with the signatures of the contract that is deployed there (aave-dao/aave-v3-origin). Its
     * repayETH and borrowETH take no rate mode: the gateway always uses the variable rate. The first argument of each function is unused.
     */
    private val AAVE_GATEWAY: Map<String, Known> = mapOf(
        "0x474cf53d" to Known("depositETH(address,address,uint16)") { c ->
            val line = "Supply ${c.nativeAmount(c.sent)} to $AAVE_V3 as WETH on behalf of ${c.who(c.address(1))}${referral(c.uint(2))}"
            CallSteps(listOf(line), notYou(c.from, SUPPLIES_TO, c.address(1)))
        },
        "0x80500d20" to Known("withdrawETH(address,uint256,address)") { c ->
            val line = "Withdraw ${nativeOrAll(c, c.uint(1))} from $AAVE_V3 to ${c.who(c.address(2))}"
            CallSteps(listOf(line), notYou(c.from, WITHDRAWS_TO, c.address(2)))
        },
        "0xbcc3c255" to Known("repayETH(address,uint256,address)") { c ->
            val amount = c.uint(1)
            val what = if (amount == ContractCall.MAX_UINT256) "all ETH owed" else "up to ${c.nativeAmount(amount)}"
            val line = "Repay $what to $AAVE_V3 at a variable rate on behalf of ${c.who(c.address(2))}, sending " +
                "${c.nativeAmount(c.sent)} (any excess is refunded)"
            CallSteps(listOf(line), notYou(c.from, REPAYS_DEBT_OF, c.address(2)))
        },
        "0xe74f7b85" to Known("borrowETH(address,uint256,uint16)") { c ->
            val line = "Borrow ${c.nativeAmount(c.uint(1))} from $AAVE_V3 at a variable rate, sent to you${referral(c.uint(2))}"
            CallSteps(listOf(line), emptyList())
        },
    )

    private fun compound(market: CompoundMarket): Map<String, Known> = mapOf(
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

    /**
     * The withdrawal to another account of a Compound market, approved on Ethereum. It pays an address that may not be the signer, so the
     * review warns when it is not.
     */
    private fun compoundWithdrawTo(market: CompoundMarket): Map<String, Known> = mapOf(
        "0xc3b35a7e" to Known("withdrawTo(address,address,uint256)") { c ->
            val to = c.address(0)
            val asset = c.address(1)
            val collateral = if (asset == market.base) "" else " collateral"
            val line = "Withdraw ${amountOrAll(c, c.uint(2), asset)}$collateral from Compound V3 (${market.symbol}) to ${c.who(to)}"
            CallSteps(listOf(line), notYou(c.from, WITHDRAWS_TO, to))
        },
    )

    /**
     * The calls that move a Compound market balance to or from another account, approved on Ethereum. Each names the account it pays or
     * takes from, and the review warns when that account is not the signer.
     */
    private fun compoundAccounts(market: CompoundMarket): Map<String, Known> = mapOf(
        "0x4232cd63" to Known("supplyTo(address,address,uint256)") { c ->
            val dst = c.address(0)
            val asset = c.address(1)
            val collateral = if (asset == market.base) "" else " as collateral"
            val recipient = if (dst == c.from) "" else " for ${shortAddress(dst)}"
            CallSteps(listOf("Supply ${c.amount(c.uint(2), asset)}$collateral to Compound V3 (${market.symbol})$recipient"), notYou(c.from, SUPPLIES_TO, dst))
        },
        "0x26441318" to Known("withdrawFrom(address,address,address,uint256)") { c ->
            val src = c.address(0)
            val to = c.address(1)
            val asset = c.address(2)
            val collateral = if (asset == market.base) "" else " collateral"
            val account = if (src == c.from) "" else " of ${shortAddress(src)}"
            val line = "Withdraw ${amountOrAll(c, c.uint(3), asset)}$collateral from Compound V3 (${market.symbol})$account to ${c.who(to)}"
            CallSteps(listOf(line), notYou(c.from, "withdraws from", src) + notYou(c.from, WITHDRAWS_TO, to))
        },
    )

    private val LIDO: Map<String, Known> = mapOf(
        "0xa1903eab" to Known("submit(address)") { c ->
            val referral = c.address(0)
            val note = if (referral == NO_ADDRESS) "" else " (referral ${shortAddress(referral)})"
            CallSteps(listOf("Stake ${c.nativeAmount(c.sent)} with Lido for stETH$note"), emptyList())
        },
    )

    /** wstETH: the wrap of stETH into it, and its unwrap. Lido's stETH is the token the wrap takes, and it is named by its address. */
    private val WSTETH: Map<String, Known> = mapOf(
        "0xea598cb0" to Known("wrap(uint256)") { c ->
            CallSteps(listOf("Wrap ${c.amount(c.uint(0), LIDO_CONTRACT)} into wstETH"), emptyList())
        },
        "0xde0e9a3e" to Known("unwrap(uint256)") { c ->
            CallSteps(listOf("Unwrap ${c.amount(c.uint(0), LIDO_WSTETH)} into stETH"), emptyList())
        },
    )

    /** Lido's withdrawal queue: a request for stETH to be paid out as ETH, and the claim of a finished request. */
    private val LIDO_WITHDRAWALS: Map<String, Known> = mapOf(
        "0xd6681042" to Known("requestWithdrawals(uint256[],address)") { c ->
            val owner = c.address(1)
            val lines = c.items(0).map { amount ->
                "Request a withdrawal of ${c.amount(amount.word(), LIDO_CONTRACT)} as ETH for ${c.who(owner)}"
            }
            val warnings = if (owner == c.from || lines.isEmpty()) emptyList() else listOf(
                "The withdrawal belongs to ${shortAddress(owner)}, which claims the ETH, not you",
            )
            CallSteps(lines.ifEmpty { listOf("Request no Lido withdrawals") }, warnings)
        },
        "0xe3afe0a3" to Known("claimWithdrawals(uint256[],uint256[])") { c ->
            val ids = c.items(0).map { "#" + it.word() }
            val noun = if (ids.size == 1) "request" else "requests"
            val line = if (ids.isEmpty()) {
                "Claim no Lido withdrawals"
            } else {
                "Claim the ETH of Lido withdrawal $noun ${ids.joinToString(", ")} to you"
            }
            CallSteps(listOf(line), emptyList())
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

    /**
     * Every approved call, by network and contract, then by selector. Built after the tables above, which it reads. The Aave pool's
     * permit variants and the gateway, the Compound withdrawal to another account, and the Lido queue are approved on Ethereum only.
     */
    private val BY_CONTRACT: Map<Pair<Long, String>, Map<String, Known>> = buildMap {
        AAVE_POOLS.forEach { put(it, AAVE) }
        put(1L to AAVE_POOL_MAINNET, AAVE + AAVE_PERMIT)
        put(1L to AAVE_WETH_GATEWAY, AAVE_GATEWAY)
        COMPOUND_MARKETS.forEach { (key, market) ->
            put(key, compound(market) + if (key.first == 1L) compoundWithdrawTo(market) + compoundAccounts(market) else emptyMap())
        }
        WRAPPED_NATIVE.forEach { put(it, WRAPPED) }
        put(1L to LIDO_CONTRACT, LIDO)
        put(1L to LIDO_WSTETH, WSTETH)
        put(1L to LIDO_QUEUE, LIDO_WITHDRAWALS)
    }

    /** The Compound V3 market at [comet] on [chainId], or null when Lensly does not know it. */
    internal fun compoundMarket(chainId: Long, comet: String): CompoundMarket? = COMPOUND_MARKETS[chainId to comet]

    /** The steps of [call] when it is an approved Aave, Compound, Lido or wrapped-coin call, and null for any other call. */
    fun describe(call: StepCall, selector: String, signature: String): CallSteps? {
        val known = BY_CONTRACT[call.chainId to call.contract]?.get(selector) ?: return null
        if (known.signature != signature) return null
        return known.describe(call)
    }

    /** An amount of the whole balance reads as "all" of the token, and any other as its quantity. */
    private fun amountOrAll(c: StepCall, amount: BigInteger, token: String): String =
        if (amount == ContractCall.MAX_UINT256) "all ${c.symbolOf(token)}" else c.amount(amount, token)

    /** An amount of the whole balance reads as "all" of the native coin, and any other as its quantity. */
    private fun nativeOrAll(c: StepCall, amount: BigInteger): String =
        if (amount == ContractCall.MAX_UINT256) "all ${c.network.nativeSymbol}" else c.nativeAmount(amount)

    private fun referral(code: BigInteger): String = if (code.signum() == 0) "" else " (referral code $code)"

    private fun rateText(mode: BigInteger): String = when (mode) {
        VARIABLE_RATE -> "a variable rate"
        BigInteger.ONE -> "a stable rate"
        else -> "interest rate mode $mode"
    }

    private fun rateWarnings(mode: BigInteger): List<String> =
        if (mode == BigInteger.ONE || mode == VARIABLE_RATE) {
            emptyList()
        } else {
            listOf("Lensly does not know Aave interest rate mode $mode, which the pool reverts")
        }
}
