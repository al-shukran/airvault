package app.lensly.core.registry

import app.airvault.abi.AbiDecoder
import app.airvault.abi.AbiValue
import app.airvault.abi.CallDecode
import app.airvault.abi.DecodedParam
import app.airvault.abi.WhitelistParam
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric

private const val SWAP_ROUTER = "0x68b3465833fb72a70ecdf485e0e4c7bd8665fc45"
private const val SINGLE = "(address,address,uint24,address,uint256,uint256,uint160)"
private const val PATH = "(bytes,address,uint256,uint256)"
private const val EXACT_INPUT_SINGLE = "0x04e45aaf"
private const val EXACT_INPUT_SINGLE_SIG = "exactInputSingle($SINGLE)"
private const val EXACT_OUTPUT_SINGLE = "0x5023b4df"
private const val EXACT_OUTPUT_SINGLE_SIG = "exactOutputSingle($SINGLE)"
private const val EXACT_INPUT = "0xb858183f"
private const val EXACT_INPUT_SIG = "exactInput($PATH)"
private const val EXACT_OUTPUT = "0x09b81346"
private const val EXACT_OUTPUT_SIG = "exactOutput($PATH)"
private const val MULTICALL = "0xac9650d8"
private const val MULTICALL_SIG = "multicall(bytes[])"
private const val MULTICALL_DEADLINE = "0x5ae401dc"
private const val MULTICALL_DEADLINE_SIG = "multicall(uint256,bytes[])"
private const val FLAG_SIGNER = "0x0000000000000000000000000000000000000001"
private const val FLAG_ROUTER = "0x0000000000000000000000000000000000000002"
private const val FUZZ_ROUNDS = 1000

/** The sender of each vector. The steps name the sender as "you", so a vector is read as its sender signed it. */
private const val SENDER_EXACT_INPUT_SINGLE = "0x75465a384de0bad0733ff1f8398f6de4d3230ecf"
private const val SENDER_EXACT_OUTPUT_SINGLE = "0xd3f40f56ad262514abb1da7ad565697a7d79ae31"
private const val SENDER_TWO_POOLS = "0x6f858f383842c887a82d30b392cfc686938413ec"
private const val SENDER_UNWRAP = "0xebd7958db820717dca5b26a5aa31e27c442b7ed4"
private const val SENDER_REFUND = "0x20414830660bd39e6245978452ce4ac188524100"
private const val SENDER_UNREAD = "0x3d63c340fa1735637e962e3b0b7d0f7cf1f722a1"

private val SINGLE_PARAMS = listOf(WhitelistParam("params", SINGLE))
private val PATH_PARAMS = listOf(WhitelistParam("params", PATH))
private val MULTICALL_PARAMS = listOf(WhitelistParam("data", "bytes[]"))
private val DEADLINE_PARAMS = listOf(WhitelistParam("deadline", "uint256"), WhitelistParam("data", "bytes[]"))

/** The steps of the real [calldata] sent to the SwapRouter02 by [from], decoded with [params] as the sandbox decodes an approved call. */
private fun stepsOf(calldata: String, selector: String, signature: String, params: List<WhitelistParam>, from: String): CallSteps? {
    val decoded = (AbiDecoder.decode(Numeric.hexStringToByteArray(calldata), params) as CallDecode.Ok).params
    return CallSteps.describe(STEP_REGISTRY, 1, SWAP_ROUTER, selector, signature, from, BigInteger.ZERO, decoded)
}

/** The one argument of a single-pool swap: a tuple of seven words, read as the sandbox decodes it. */
private fun singlePoolArgs(
    tokenIn: String,
    tokenOut: String,
    fee: BigInteger,
    recipient: String,
    amount: BigInteger,
    limit: BigInteger,
    sqrtPriceLimit: BigInteger,
): List<DecodedParam> = listOf(
    DecodedParam(
        SINGLE_PARAMS[0],
        AbiValue.Items(
            listOf(
                addressWord(tokenIn),
                addressWord(tokenOut),
                word(fee),
                addressWord(recipient),
                word(amount),
                word(limit),
                word(sqrtPriceLimit),
            ),
        ),
    ),
)

private fun word(value: BigInteger): AbiValue = AbiValue.Word(value.toString(16).padStart(ABI_WORD * 2, '0'))

private fun addressWord(address: String): AbiValue = AbiValue.Word(address.removePrefix("0x").padStart(ABI_WORD * 2, '0'))

/** A 256-bit word of random bits, as an argument a malformed call might carry. */
private fun randomWord(random: Random): BigInteger = BigInteger(1, random.nextBytes(ABI_WORD))

private fun randomAddress(random: Random): String = "0x" + random.nextBytes(20).toHex()

class SwapRouterStepsTest {

    @Test
    fun anExactInputSingleSwapNamesTheTokensTheAmountAndTheMinimum() {
        // Transaction 0xa3c2ae3b: 93.6 USDT to USDC through the 0.01% pool.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.SWAP_ROUTER_EXACT_INPUT_SINGLE,
                EXACT_INPUT_SINGLE,
                EXACT_INPUT_SINGLE_SIG,
                SINGLE_PARAMS,
                SENDER_EXACT_INPUT_SINGLE,
            ),
        )

        assertEquals(listOf("Swap exactly 93.6 USDT for ≥ 93.056291 USDC via Uniswap v3 (0.01%)"), steps.lines)
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun anExactOutputSingleSwapNamesTheMaximumPaidAndTheExactAmountBought() {
        // Transaction 0x34f17f0f: buys 40,000,000,000 base units of an unregistered token for at most 6.513472 USDT, through the 0.3% pool.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.SWAP_ROUTER_EXACT_OUTPUT_SINGLE,
                EXACT_OUTPUT_SINGLE,
                EXACT_OUTPUT_SINGLE_SIG,
                SINGLE_PARAMS,
                SENDER_EXACT_OUTPUT_SINGLE,
            ),
        )

        assertEquals(
            listOf("Swap up to 6.513472 USDT for exactly 40000000000 base units of 0x972a…2F68 via Uniswap v3 (0.3%)"),
            steps.lines,
        )
    }

    @Test
    fun anExactInputAcrossTwoPoolsNamesEachPoolsFee() {
        // Transaction 0xdf441b7a: 50 USDT through WETH to 0x8CD4…ae7E, through the 0.01% pool and then the 1% pool.
        val steps = assertNotNull(
            stepsOf(StepVectors.SWAP_ROUTER_EXACT_INPUT, EXACT_INPUT, EXACT_INPUT_SIG, PATH_PARAMS, SENDER_TWO_POOLS),
        )

        assertEquals(
            listOf(
                "Swap exactly 50 USDT for ≥ 1356863935555867246592 base units of 0x8CD4…ae7E via Uniswap v3: " +
                    "USDT → WETH (0.01%), WETH → 0x8CD4…ae7E (1%)",
            ),
            steps.lines,
        )
    }

    @Test
    fun anExactOutputAcrossTwoPoolsReadsThePathFromTheTokenBoughtBack() {
        // Transaction 0x6aa08d4b: buys 100 USDT through WETH, paying 0x8CD4…ae7E, for at most 2919943589650316656640 base units.
        // The path of an exact output lists the token bought first, so the steps read it from the other end.
        val steps = assertNotNull(
            stepsOf(StepVectors.SWAP_ROUTER_EXACT_OUTPUT, EXACT_OUTPUT, EXACT_OUTPUT_SIG, PATH_PARAMS, SENDER_TWO_POOLS),
        )

        assertEquals(
            listOf(
                "Swap up to 2919943589650316656640 base units of 0x8CD4…ae7E for exactly 100 USDT via Uniswap v3: " +
                    "0x8CD4…ae7E → WETH (1%), WETH → USDT (0.01%)",
            ),
            steps.lines,
        )
    }

    @Test
    fun aMulticallOfASwapThatKeepsItsWethInTheRouterAndAnUnwrapNamesEachCall() {
        // Transaction 0x0188e534: an exactInputSingle that keeps its output in the router, then unwrapWETH9 to the sender.
        val steps = assertNotNull(
            stepsOf(StepVectors.SWAP_ROUTER_MULTICALL_UNWRAP, MULTICALL, MULTICALL_SIG, MULTICALL_PARAMS, SENDER_UNWRAP),
        )

        assertEquals(
            listOf(
                "Swap exactly 421723804899518709760 base units of 0xE7E7…E7E7 for ≥ 0 WETH via Uniswap v3 (1%), kept in the router",
                "Unwrap all WETH the router holds → ETH for you",
            ),
            steps.lines,
        )
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun aDeadlineMulticallOfAnExactOutputAndARefundNamesEachCall() {
        // Transaction 0x227d6d9e: buys 8.378456 USDC with WETH for the sender, then refunds the router's ETH to the sender.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.SWAP_ROUTER_DEADLINE_MULTICALL_REFUND,
                MULTICALL_DEADLINE,
                MULTICALL_DEADLINE_SIG,
                DEADLINE_PARAMS,
                SENDER_REFUND,
            ),
        )

        assertEquals(
            listOf(
                "Swap up to 0.004719151327006032 WETH for exactly 8.378456 USDC via Uniswap v3 (0.05%)",
                "Refund all ETH the router holds to you",
            ),
            steps.lines,
        )
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun aCallInsideAMulticallLenslyDoesNotReadIsShownRawWithAWarning() {
        // Transaction 0x03eb2514: command 0x472b43f3 is swapExactTokensForTokens, a Uniswap v2 swap that Lensly does not read.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.SWAP_ROUTER_DEADLINE_MULTICALL_UNREAD,
                MULTICALL_DEADLINE,
                MULTICALL_DEADLINE_SIG,
                DEADLINE_PARAMS,
                SENDER_UNREAD,
            ),
        )

        assertTrue(steps.lines.single().startsWith("Unknown router call 0x472b43f3: raw input 0x472b43f3"), steps.lines.single())
        assertEquals(listOf("Lensly cannot read router call 0x472b43f3 in this multicall. Its raw input is under Details."), steps.warnings)
    }

    @Test
    fun aMulticallInsideAMulticallIsNotReadAndWarns() {
        // No real transaction holds a multicall inside a multicall, so the inner one stays raw. This input is built to test that path.
        val inner = MULTICALL.removePrefix("0x") + "00".repeat(ABI_WORD * 2)
        val args = listOf(DecodedParam(MULTICALL_PARAMS[0], AbiValue.Items(listOf(AbiValue.Dynamic(inner)))))

        val steps = assertNotNull(
            CallSteps.describe(STEP_REGISTRY, 1, SWAP_ROUTER, MULTICALL, MULTICALL_SIG, SENDER_UNREAD, BigInteger.ZERO, args),
        )

        assertEquals(listOf("Router call 0xac9650d8 could not be read: raw input 0x$inner"), steps.lines)
        assertEquals(listOf("Lensly cannot read the input of router call 0xac9650d8. Its raw input is under Details."), steps.warnings)
    }

    @Test
    fun aSwapWithAPriceLimitIsNotReadAndWarns() {
        // The USDT to USDC swap of the vector, with a price limit set. The limit can stop the swap before its amount is spent.
        val args = singlePoolArgs(
            USDT,
            USDC,
            BigInteger.valueOf(100),
            SENDER_EXACT_INPUT_SINGLE,
            BigInteger.valueOf(93_600_000),
            BigInteger.valueOf(93_056_291),
            BigInteger.ONE,
        )

        val steps = assertNotNull(
            CallSteps.describe(STEP_REGISTRY, 1, SWAP_ROUTER, EXACT_INPUT_SINGLE, EXACT_INPUT_SINGLE_SIG, SENDER_EXACT_INPUT_SINGLE, BigInteger.ZERO, args),
        )

        assertEquals(emptyList(), steps.lines)
        assertEquals(listOf(UNREADABLE_CALL), steps.warnings)
    }

    @Test
    fun aFeeWiderThanAUint24IsNotReadAndWarns() {
        val args = singlePoolArgs(
            USDT,
            USDC,
            BigInteger.ONE.shiftLeft(24),
            SENDER_EXACT_INPUT_SINGLE,
            BigInteger.valueOf(93_600_000),
            BigInteger.valueOf(93_056_291),
            BigInteger.ZERO,
        )

        val steps = assertNotNull(
            CallSteps.describe(STEP_REGISTRY, 1, SWAP_ROUTER, EXACT_INPUT_SINGLE, EXACT_INPUT_SINGLE_SIG, SENDER_EXACT_INPUT_SINGLE, BigInteger.ZERO, args),
        )

        assertEquals(listOf(UNREADABLE_CALL), steps.warnings)
    }

    @Test
    fun theRecipientFlagsNameTheSignerAndTheRouter() {
        // V3SwapRouter.sol reads address(1) as the signer and address(2) as the router, so the steps name them that way.
        fun steps(recipient: String): CallSteps? = CallSteps.describe(
            STEP_REGISTRY,
            1,
            SWAP_ROUTER,
            EXACT_INPUT_SINGLE,
            EXACT_INPUT_SINGLE_SIG,
            SENDER_EXACT_INPUT_SINGLE,
            BigInteger.ZERO,
            singlePoolArgs(
                USDT,
                USDC,
                BigInteger.valueOf(100),
                recipient,
                BigInteger.valueOf(93_600_000),
                BigInteger.valueOf(93_056_291),
                BigInteger.ZERO,
            ),
        )

        assertEquals(
            listOf("Swap exactly 93.6 USDT for ≥ 93.056291 USDC via Uniswap v3 (0.01%)"),
            assertNotNull(steps(FLAG_SIGNER)).lines,
        )
        assertEquals(
            listOf("Swap exactly 93.6 USDT for ≥ 93.056291 USDC via Uniswap v3 (0.01%), kept in the router"),
            assertNotNull(steps(FLAG_ROUTER)).lines,
        )
    }

    @Test
    fun aSignatureOtherThanTheApprovedOneIsNotDescribed() {
        // The flattened form of exactInputSingle is not the approved signature, so Lensly does not describe the call.
        val args = singlePoolArgs(
            USDT,
            USDC,
            BigInteger.valueOf(100),
            SENDER_EXACT_INPUT_SINGLE,
            BigInteger.valueOf(93_600_000),
            BigInteger.valueOf(93_056_291),
            BigInteger.ZERO,
        )

        assertNull(
            CallSteps.describe(
                STEP_REGISTRY,
                1,
                SWAP_ROUTER,
                EXACT_INPUT_SINGLE,
                "exactInputSingle(address,address,uint24,address,uint256,uint256,uint160)",
                SENDER_EXACT_INPUT_SINGLE,
                BigInteger.ZERO,
                args,
            ),
        )
    }

    @Test
    fun aCallToAnotherContractWithTheSameSelectorIsNotDescribed() {
        val args = singlePoolArgs(
            USDT,
            USDC,
            BigInteger.valueOf(100),
            SENDER_EXACT_INPUT_SINGLE,
            BigInteger.valueOf(93_600_000),
            BigInteger.valueOf(93_056_291),
            BigInteger.ZERO,
        )

        assertNull(
            CallSteps.describe(
                STEP_REGISTRY,
                1,
                "0x0000000000000000000000000000000000000abc",
                EXACT_INPUT_SINGLE,
                EXACT_INPUT_SINGLE_SIG,
                SENDER_EXACT_INPUT_SINGLE,
                BigInteger.ZERO,
                args,
            ),
        )
    }

    @Test
    fun randomSwapArgumentsAreReadOrWarnAndNeverThrow() {
        val random = Random(20261116)
        val network = assertNotNull(STEP_REGISTRY.network(1L))
        repeat(FUZZ_ROUNDS) { round ->
            val exactIn = round % 2 == 0
            val fee = if (round % 4 == 0) BigInteger.valueOf(random.nextLong(0, 1L shl 24)) else randomWord(random)
            val sqrtPriceLimit = if (round % 3 == 0) BigInteger.ZERO else randomWord(random)
            val args = singlePoolArgs(
                randomAddress(random),
                randomAddress(random),
                fee,
                randomAddress(random),
                randomWord(random),
                randomWord(random),
                sqrtPriceLimit,
            )
            val call = StepCall(STEP_REGISTRY, network, SWAP_ROUTER, SENDER_EXACT_INPUT_SINGLE, BigInteger.ZERO, args)

            val steps = assertNotNull(
                if (exactIn) {
                    SwapRouterSteps.describe(call, EXACT_INPUT_SINGLE, EXACT_INPUT_SINGLE_SIG)
                } else {
                    SwapRouterSteps.describe(call, EXACT_OUTPUT_SINGLE, EXACT_OUTPUT_SINGLE_SIG)
                },
            )
            assertTrue(steps.lines.isNotEmpty() || steps.warnings.isNotEmpty())
        }
    }

    @Test
    fun randomMulticallsAreReadOrWarnAndNeverThrow() {
        val random = Random(20261117)
        val network = assertNotNull(STEP_REGISTRY.network(1L))
        val selectors = listOf(
            "04e45aaf", "b858183f", "5023b4df", "09b81346", "49404b7c", "49616997", "df2ab5bb", "e90a182f", "12210e8a", "472b43f3",
            "ac9650d8", "5ae401dc",
        )
        repeat(FUZZ_ROUNDS) {
            val items = List(random.nextInt(0, 4)) {
                val selector = if (random.nextInt(4) == 0) random.nextBytes(4).toHex() else selectors[random.nextInt(selectors.size)]
                AbiValue.Dynamic(selector + random.nextBytes(random.nextInt(0, 260)).toHex())
            }
            val args = listOf(DecodedParam(MULTICALL_PARAMS[0], AbiValue.Items(items)))
            val call = StepCall(STEP_REGISTRY, network, SWAP_ROUTER, SENDER_EXACT_INPUT_SINGLE, BigInteger.ZERO, args)

            val steps = assertNotNull(SwapRouterSteps.describe(call, MULTICALL, MULTICALL_SIG))
            assertTrue(steps.lines.isNotEmpty() || steps.warnings.isNotEmpty())
        }
    }
}
