package app.lensly.core.registry

import app.airvault.abi.AbiDecoder
import app.airvault.abi.AbiValue
import app.airvault.abi.CallDecode
import app.airvault.abi.DecodedParam
import app.airvault.abi.WhitelistParam
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric

private const val UR_V2 = "0x66a9893cc07d91d95644aedd05d03f95e1dba8af"
private const val EXECUTE = "0x3593564c"
private const val EXECUTE_SIGNATURE = "execute(bytes,bytes[],uint256)"
private const val NO_HOOK = "0x0000000000000000000000000000000000000000"
private const val MULTICALL3 = "0xca11bde05977b3631167028862be2a173976ca11"
private const val OTHER = "0x00000000000000000000000000000000000000aa"

/** ActionConstants.CONTRACT_BALANCE: a settle amount for the router's whole balance. */
private val CONTRACT_BALANCE: BigInteger = BigInteger.ONE.shiftLeft(255)

private const val UNREAD_WARNING = "Lensly cannot read the input of command 0x10. Its raw input is under Details."

/** The arguments of `execute(bytes,bytes[],uint256)`, in the order the approved entry names them. */
private val EXECUTE_PARAMS = listOf(
    WhitelistParam("commands", "bytes"),
    WhitelistParam("inputs", "bytes[]"),
    WhitelistParam("deadline", "uint256"),
)

/** The steps of a real [calldata] that UR V2 runs for [from], decoded as the sandbox decodes an approved call. */
private fun stepsOf(calldata: String, from: String): CallSteps? {
    val decoded = (AbiDecoder.decode(Numeric.hexStringToByteArray(calldata), EXECUTE_PARAMS) as CallDecode.Ok).params
    return CallSteps.describe(STEP_REGISTRY, 1, UR_V2, EXECUTE, EXECUTE_SIGNATURE, from, BigInteger.ZERO, decoded)
}

/** The steps of a call whose only command is V4_SWAP (0x10) with [input], sent by the signer, or by [caller] when a batch makes the call. */
private fun stepsOfV4(input: ByteArray, caller: String = SIGNER): CallSteps? = CallSteps.describe(
    STEP_REGISTRY,
    1,
    UR_V2,
    EXECUTE,
    EXECUTE_SIGNATURE,
    SIGNER,
    BigInteger.ZERO,
    listOf(
        DecodedParam(EXECUTE_PARAMS[0], AbiValue.Dynamic("10")),
        DecodedParam(EXECUTE_PARAMS[1], AbiValue.Items(listOf(AbiValue.Dynamic(input.toHex())))),
        DecodedParam(EXECUTE_PARAMS[2], AbiValue.Word("00".repeat(ABI_WORD))),
    ),
    caller,
)

/** Asserts that a V4_SWAP command with [input] is shown raw, with its warning, and no partial summary. */
private fun assertShownRaw(input: ByteArray) {
    val steps = assertNotNull(stepsOfV4(input))
    assertEquals(listOf("Command 0x10 could not be read: raw input 0x${input.toHex()}"), steps.lines)
    assertEquals(listOf(UNREAD_WARNING), steps.warnings)
}

private fun word(value: Long): ByteArray = abiWord(BigInteger.valueOf(value))

/** The ABI encoding of [data] as a bytes value: its length, then its bytes padded to whole words. */
private fun bytesOf(data: ByteArray): ByteArray =
    word(data.size.toLong()) + data + ByteArray((ABI_WORD - data.size % ABI_WORD) % ABI_WORD)

/** The ABI encoding of an array of dynamic [elements]: the count, an offset to each element, then the elements. */
private fun dynamicArray(elements: List<ByteArray>): ByteArray {
    var offset = (elements.size * ABI_WORD).toLong()
    var heads = ByteArray(0)
    var tails = ByteArray(0)
    for (element in elements) {
        heads += word(offset)
        tails += element
        offset += element.size
    }
    return word(elements.size.toLong()) + heads + tails
}

/** The input of command 0x10: the ABI encoding of (bytes actions, bytes[] params). */
private fun v4Input(actions: ByteArray, params: List<ByteArray>): ByteArray {
    val actionBytes = bytesOf(actions)
    return word(2L * ABI_WORD) + word(2L * ABI_WORD + actionBytes.size) + actionBytes + dynamicArray(params.map { bytesOf(it) })
}

/** A pool key (currency0, currency1, fee, tickSpacing 60, hooks), whose five static words sit inline in its struct. */
private fun poolKey(currency0: String, currency1: String, fee: Long, hooks: String = NO_HOOK): ByteArray =
    abiAddressWord(currency0) + abiAddressWord(currency1) + word(fee) + word(60) + abiAddressWord(hooks)

/** The argument of a single-pool swap: (poolKey, zeroForOne, amount, limit, hookData), as one dynamic struct. */
private fun singlePool(key: ByteArray, zeroForOne: Boolean, amount: BigInteger, limit: BigInteger): ByteArray =
    word(ABI_WORD.toLong()) + key + word(if (zeroForOne) 1L else 0L) + abiWord(amount) + abiWord(limit) + word(9L * ABI_WORD) + word(0)

/** One hop of a path: (intermediate, fee, tickSpacing 60, hooks, hookData), as one dynamic struct. */
private fun hop(token: String, fee: Long, hooks: String = NO_HOOK): ByteArray =
    abiAddressWord(token) + word(fee) + word(60) + abiAddressWord(hooks) + word(5L * ABI_WORD) + word(0)

/** The argument of a multi-pool swap: (currency, path, amount, limit), as one dynamic struct. */
private fun pathSwap(currency: String, hops: List<ByteArray>, amount: BigInteger, limit: BigInteger): ByteArray =
    word(ABI_WORD.toLong()) + abiAddressWord(currency) + word(4L * ABI_WORD) + abiWord(amount) + abiWord(limit) + dynamicArray(hops)

/** The argument of a two-word action, such as settle-all (currency, maximum) or take-all (currency, minimum). */
private fun pairParam(currency: String, amount: BigInteger): ByteArray = abiAddressWord(currency) + abiWord(amount)

/** The argument of a settle: (currency, amount, payerIsUser). */
private fun settleParam(currency: String, amount: BigInteger, payerIsUser: Boolean): ByteArray =
    abiAddressWord(currency) + abiWord(amount) + word(if (payerIsUser) 1L else 0L)

/** The argument of a take: (currency, recipient, amount). */
private fun takeParam(currency: String, recipient: String, amount: BigInteger): ByteArray =
    abiAddressWord(currency) + abiAddressWord(recipient) + abiWord(amount)

/** The argument of a take-portion: (currency, recipient, bips), with the recipient the signer's flag, so the share goes to the signer. */
private fun takePortionParam(bips: BigInteger): ByteArray = abiAddressWord(USDC) + abiAddressWord(MSG_SENDER) + abiWord(bips)

class V4SwapStepsTest {

    @Test
    fun anExactInputSwapFromEthReadsItsSwapSettleAndTakeAll() {
        // Transaction 0xcd9bb2ab02b0ae29c5c2db0b512faf598341a30eafee971ab7f3c6afbd733667, on Universal Router V2: a swap of 0.2985 ETH with no
        // minimum, then SETTLE_ALL and TAKE_ALL.
        val steps = assertNotNull(
            stepsOf(StepVectors.V4_SWAP_EXACT_IN_SINGLE_TAKE_ALL, "0x5ea7fcf5a8ac7a5d9aaa762ed9ffdd98323c703e"),
        )

        assertEquals(
            listOf(
                "Swap exactly 0.2985 ETH for ≥ 0 base units of 0xf944 AbFB … 0c84 D7E9 via Uniswap v4 (0%)",
                "Pay all ETH the router owes to Uniswap v4 (at most 0.2985 ETH)",
                "Take all 0xf944 AbFB … 0c84 D7E9 owed to the router from Uniswap v4 to you",
            ),
            steps.lines,
        )
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun aTakeToAnotherAddressWarnsAndATakeToTheSignerOrTheRouterDoesNot() {
        fun take(recipient: String) = assertNotNull(stepsOfV4(v4Input(byteArrayOf(0x0e), listOf(takeParam(USDC, recipient, BigInteger.TEN)))))

        assertEquals(listOf("This sends the swap output to ${shortAddress(OTHER)}, not you"), take(OTHER).warnings)
        assertEquals(listOf(OTHER), take(OTHER).addresses)
        listOf(SIGNER, MSG_SENDER, ADDRESS_THIS).forEach { own ->
            assertEquals(emptyList(), take(own).warnings, own)
            assertEquals(emptyList(), take(own).addresses, own)
        }
    }

    @Test
    fun aTakeInsideABatchPaysTheBatchContractWhereTheFlagOrTakeAllNamesTheMessageSender() {
        val take = v4Input(byteArrayOf(0x0e), listOf(takeParam(USDC, MSG_SENDER, BigInteger.TEN)))
        val takeAll = v4Input(byteArrayOf(0x0f), listOf(pairParam(USDC, BigInteger.ZERO)))
        val settleAll = v4Input(byteArrayOf(0x0c), listOf(pairParam(USDC, BigInteger.ONE)))

        val viaTake = assertNotNull(stepsOfV4(take, MULTICALL3))
        assertEquals(listOf("Take 0.00001 USDC from Uniswap v4 to Multicall3"), viaTake.lines)
        assertEquals(listOf("This sends the swap output to 0xcA11 bde0 … 3976 CA11, not you"), viaTake.warnings)
        assertEquals(listOf(MULTICALL3), viaTake.addresses)

        val viaTakeAll = assertNotNull(stepsOfV4(takeAll, MULTICALL3))
        assertEquals(listOf("Take all USDC owed to the router from Uniswap v4 to Multicall3"), viaTakeAll.lines)
        assertEquals(listOf(MULTICALL3), viaTakeAll.addresses)

        assertEquals(listOf("Pay all USDC the router owes from Multicall3 to Uniswap v4 (at most 0.000001 USDC)"), assertNotNull(stepsOfV4(settleAll, MULTICALL3)).lines)
        assertEquals(emptyList(), assertNotNull(stepsOfV4(takeAll)).addresses)
    }

    @Test
    fun anExactInputSwapToAnotherAccountNamesThatAccountInItsTake() {
        // Transaction 0x1ce3654a423cfd4fac1c3d63dc666eb141a21b2773b8304aa5073e44ba0ab93f, on Universal Router V2: a swap of a token for ETH,
        // then SETTLE_ALL, then TAKE of the whole credit to another account.
        val steps = assertNotNull(stepsOf(StepVectors.V4_SWAP_EXACT_IN_SINGLE_TAKE, "0x15a8a988b7f130caa519a8b5d422eca4f124e9f6"))

        assertEquals(
            listOf(
                "Swap exactly 48076295626582967852933 base units of 0xf944 AbFB … 0c84 D7E9 for ≥ 0 ETH via Uniswap v4 (0%)",
                "Pay all 0xf944 AbFB … 0c84 D7E9 the router owes from you to Uniswap v4 " +
                    "(at most 48076295626582967852933 base units of 0xf944 AbFB … 0c84 D7E9)",
                "Take all ETH owed to the router from Uniswap v4 to 0xFb35 6A46 … d352 AF75",
            ),
            steps.lines,
        )
        assertEquals(listOf("This sends the swap output to 0xFb35 6A46 … d352 AF75, not you"), steps.warnings)
        assertEquals("0xfb35", steps.addresses.single().take(6))
    }

    @Test
    fun anExactInputPathReadsEachPoolInTradeOrderWithItsFee() {
        // Transaction 0xef0ec9d21dc1a5a336e95e7a7d8aea1a8cc9cfe9881fd7d882947d78b34fc332, on Universal Router V2: an exact-input swap of 1,494 tokens
        // through USDC and then USDT, both pools without hooks, then SETTLE_ALL and TAKE_ALL.
        val steps = assertNotNull(stepsOf(StepVectors.V4_SWAP_EXACT_IN_PATH, "0x5618ec2a0accfe92ea6c2b77676dee7342225797"))

        assertEquals(
            listOf(
                "Swap exactly 1494000000000000000000 base units of 0x118B 70dF … 8C8e a0c0 for ≥ 197.899096 USDT " +
                    "via Uniswap v4: 0x118B 70dF … 8C8e a0c0 → USDC (0.25%), USDC → USDT (0.0007%)",
                "Pay all 0x118B 70dF … 8C8e a0c0 the router owes from you to Uniswap v4 (at most 1494000000000000000000 base units of 0x118B 70dF … 8C8e a0c0)",
                "Take all USDT owed to the router from Uniswap v4 to you (at least 197.899096 USDT)",
            ),
            steps.lines,
        )
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun aSettleFromTheRouterNamesNoPayerForEth() {
        // Transaction 0xd03ddeb55a2196a335e78bd2258419110c593e0101d638bf24c4f2c3f3fa5336, on Universal Router V2: a swap of 0.121 ETH, then SETTLE
        // of that ETH with payerIsUser false, then TAKE_ALL. A native coin is paid from the router's balance, so the settle names no payer.
        val steps = assertNotNull(stepsOf(StepVectors.V4_SETTLE_ROUTER_PAYER, "0x813517f571f282af5a1902f94332bee8c2cbae51"))

        assertEquals(
            listOf(
                "Swap exactly 0.121 ETH for ≥ 6551442498777280720010022 base units of 0x2273 0064 … ec2E 171b via Uniswap v4 (0.3%)",
                "Pay 0.121 ETH to Uniswap v4",
                "Take all 0x2273 0064 … ec2E 171b owed to the router from Uniswap v4 to you",
            ),
            steps.lines,
        )
        assertEquals(
            listOf(
                "A pool hook at 0x3Dc1 0d9E … dc27 a145 runs its own code during this swap, so the amounts can differ from those shown.",
            ),
            steps.warnings,
        )
    }

    @Test
    fun aTakePortionOfTheWholeCreditNamesItsShareAndItsAccount() {
        // Transaction 0x367e8e9ae8b22bafa7afdf7abd85e54285d3cbbfe082a51336a0935fec41d15f, on Universal Router V2: a swap with no minimum, SETTLE_ALL
        // with no maximum, then TAKE_PORTION of 10000 basis points of the ETH to another account.
        val steps = assertNotNull(stepsOf(StepVectors.V4_TAKE_PORTION, "0xc7008295aa37e5c99e29dc52543364c5c102a403"))

        assertEquals(
            listOf(
                "Swap exactly 7924186602868757900558336 base units of 0x2273 0064 … ec2E 171b for ≥ 0 ETH via Uniswap v4 (0.3%)",
                "Pay all 0x2273 0064 … ec2E 171b the router owes from you to Uniswap v4 (no limit)",
                "Take 100% of all ETH owed to the router from Uniswap v4 to 0xc509 5A64 … 2B60 d3d4",
            ),
            steps.lines,
        )
        assertEquals(
            listOf(
                "A pool hook at 0x3Dc1 0d9E … dc27 a145 runs its own code during this swap, so the amounts can differ from those shown.",
                "This sends part of the swap output to 0xc509 5A64 … 2B60 d3d4, not you",
            ),
            steps.warnings,
        )
        assertAddressesInGroupsOfFour(steps)
    }

    @Test
    fun anExactOutputSingleSpendsUpToItsMaximumForItsExactOutput() {
        // Transaction 0x41e30ef205794db5dfa2d23a75433eaf16fd4da38604a703456400fce62fce05, on Universal Router V2: an exact-output swap of USDT
        // for a token, then SETTLE_ALL up to the maximum, and TAKE_ALL of the token at its minimum and of USDT with no minimum.
        val steps = assertNotNull(stepsOf(StepVectors.V4_SWAP_EXACT_OUT_SINGLE, "0x5618ec2a0accfe92ea6c2b77676dee7342225797"))

        assertEquals(
            listOf(
                "Swap up to 200.402795 USDT for exactly 12207000000000000000000 base units of 0xE76c 5b78 … 582a 5dE1 via Uniswap v4 (0.25%)",
                "Pay all USDT the router owes from you to Uniswap v4 (at most 200.402795 USDT)",
                "Take all 0xE76c 5b78 … 582a 5dE1 owed to the router from Uniswap v4 to you " +
                    "(at least 12207000000000000000000 base units of 0xE76c 5b78 … 582a 5dE1)",
                "Take all USDT owed to the router from Uniswap v4 to you",
            ),
            steps.lines,
        )
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun anExactOutputPathReadsItsPoolsFromTheInputToTheOutput() {
        // Transaction 0x560ccdb1bf042887de64bb2de90cee86a1cb169a0d0f529f9019dd43648edfb6, on Universal Router V2: a Permit2 permit, an
        // exact-output swap of USDT through USDC to 0xdeF1 b2D9 … 9017 5afe, then a balance check. The path lists the input of each pool first, so the
        // first pool is USDT to USDC at 0.0007% and the second is USDC to the output at 0.25%.
        val lines = assertNotNull(stepsOf(StepVectors.V4_SWAP_EXACT_OUT_PATH, "0x8bb88a3eafd6ba0b6cce254c0c447c4cf5860afe")).lines

        assertEquals(6, lines.size)
        assertEquals(
            listOf(
                "Swap up to 225.631417 USDT for exactly 67361000000000000000000 base units of 0xdeF1 b2D9 … 9017 5afe via Uniswap v4: " +
                    "USDT → USDC (0.0007%), USDC → 0xdeF1 b2D9 … 9017 5afe (0.25%)",
                "Pay all USDT the router owes from you to Uniswap v4 (at most 225.631417 USDT)",
                "Take all 0xdeF1 b2D9 … 9017 5afe owed to the router from Uniswap v4 to you " +
                    "(at least 67361000000000000000000 base units of 0xdeF1 b2D9 … 9017 5afe)",
                "Take all USDT owed to the router from Uniswap v4 to you",
            ),
            lines.subList(1, 5),
        )
    }

    @Test
    fun aZeroAmountSwapSaysItUsesTheRoutersCreditOrDebt() {
        // A zero amount is OPEN_DELTA: an exact input spends the router's whole credit, and an exact output buys its whole debt.
        val usdcWeth = poolKey(USDC, WETH, 500)
        val exactIn = v4Input(byteArrayOf(0x06), listOf(singlePool(usdcWeth, true, BigInteger.ZERO, BigInteger.ZERO)))
        val exactOut = v4Input(byteArrayOf(0x08), listOf(singlePool(usdcWeth, true, BigInteger.ZERO, BigInteger.valueOf(10_000_000))))

        assertEquals(
            listOf("Swap all USDC owed to the router for ≥ 0 WETH via Uniswap v4 (0.05%)"),
            assertNotNull(stepsOfV4(exactIn)).lines,
        )
        assertEquals(
            listOf("Swap up to 10 USDC for all WETH the router owes via Uniswap v4 (0.05%)"),
            assertNotNull(stepsOfV4(exactOut)).lines,
        )
    }

    @Test
    fun aSettleAndATakeNameTheRoutersBalanceDebtAndRecipients() {
        // Settle amounts: CONTRACT_BALANCE is the router's whole balance, and zero is its whole debt. The payer is the signer or the router.
        val settles = v4Input(
            byteArrayOf(0x0b, 0x0b, 0x0b),
            listOf(
                settleParam(USDC, CONTRACT_BALANCE, payerIsUser = true),
                settleParam(USDC, BigInteger.ZERO, payerIsUser = false),
                settleParam(USDC, BigInteger.valueOf(5_000_000), payerIsUser = true),
            ),
        )
        // Take recipients: the flag address(1) is the signer, address(2) is the router, and any other address is itself.
        val takes = v4Input(
            byteArrayOf(0x0e, 0x0e, 0x0e),
            listOf(
                takeParam(USDC, ADDRESS_THIS, BigInteger.ZERO),
                takeParam(USDC, SIGNER, BigInteger.valueOf(5_000_000)),
                takeParam(USDC, ADDRESS_THIS, BigInteger.valueOf(5_000_000)),
            ),
        )

        assertEquals(
            listOf(
                "Pay all USDC the router holds from you to Uniswap v4",
                "Pay all USDC the router owes from the router to Uniswap v4",
                "Pay 5 USDC from you to Uniswap v4",
            ),
            assertNotNull(stepsOfV4(settles)).lines,
        )
        assertEquals(
            listOf(
                "Take all USDC owed to the router from Uniswap v4 to the router",
                "Take 5 USDC from Uniswap v4 to you",
                "Take 5 USDC from Uniswap v4 to the router",
            ),
            assertNotNull(stepsOfV4(takes)).lines,
        )
    }

    @Test
    fun aPathOfFourPoolsIsReadAndAPathOfFiveFailsTheCommand() {
        val four = listOf(hop(WETH, 500), hop(USDC, 500), hop(WETH, 500), hop(USDT, 500))
        val steps = assertNotNull(stepsOfV4(v4Input(byteArrayOf(0x07), listOf(pathSwap(USDC, four, BigInteger.valueOf(1_000_000), BigInteger.ZERO)))))

        assertEquals(
            listOf(
                "Swap exactly 1 USDC for ≥ 0 USDT via Uniswap v4: USDC → WETH (0.05%), WETH → USDC (0.05%), " +
                    "USDC → WETH (0.05%), WETH → USDT (0.05%)",
            ),
            steps.lines,
        )
        assertShownRaw(v4Input(byteArrayOf(0x07), listOf(pathSwap(USDC, four + hop(USDC, 500), BigInteger.ONE, BigInteger.ZERO))))
    }

    @Test
    fun aListOfSixteenActionsIsReadAndASeventeenthFailsTheCommand() {
        val sixteen = List(16) { pairParam(USDC, BigInteger.ZERO) }
        val steps = assertNotNull(stepsOfV4(v4Input(ByteArray(16) { 0x0f.toByte() }, sixteen)))

        assertEquals(List(16) { "Take all USDC owed to the router from Uniswap v4 to you" }, steps.lines)
        assertShownRaw(v4Input(ByteArray(17) { 0x0f.toByte() }, List(17) { pairParam(USDC, BigInteger.ZERO) }))
    }

    @Test
    fun aTakePortionAboveTheWholeCreditIsNotRead() {
        val whole = assertNotNull(stepsOfV4(v4Input(byteArrayOf(0x10), listOf(takePortionParam(BigInteger.valueOf(10_000))))))
        assertEquals(listOf("Take 100% of all USDC owed to the router from Uniswap v4 to you"), whole.lines)

        assertShownRaw(v4Input(byteArrayOf(0x10), listOf(takePortionParam(BigInteger.valueOf(10_001)))))
    }

    @Test
    fun aPoolFeeAboveTheStaticMaximumOrAPoolKeyOutOfOrderFailsTheCommand() {
        // 0x800000 is the dynamic-fee flag, and it is above MAX_LP_FEE, so a dynamic-fee pool is not read.
        assertShownRaw(v4Input(byteArrayOf(0x06), listOf(singlePool(poolKey(USDC, WETH, 0x800000), true, BigInteger.ONE, BigInteger.ZERO))))
        assertShownRaw(v4Input(byteArrayOf(0x06), listOf(singlePool(poolKey(USDC, WETH, 1_000_001), true, BigInteger.ONE, BigInteger.ZERO))))
        // The pool manager orders currency0 below currency1, so a key in any other order is not a pool.
        assertShownRaw(v4Input(byteArrayOf(0x06), listOf(singlePool(poolKey(WETH, USDC, 500), true, BigInteger.ONE, BigInteger.ZERO))))
    }

    @Test
    fun anUnreadActionFailsTheWholeCommandEvenAfterAReadableOne() {
        // The first action is readable, and the router reverts the batch at any other action ID, so none of the command is shown.
        val settle = pairParam(USDC, BigInteger.TEN)
        listOf(0x00, 0x03, 0x05, 0x0a, 0x0d, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18).forEach { id ->
            assertShownRaw(v4Input(byteArrayOf(0x0c, id.toByte()), listOf(settle, ByteArray(64))))
        }
    }

    @Test
    fun anActionWhoseParamsDoNotDecodeFailsTheWholeCommand() {
        // TAKE_ALL is two words, so 40 bytes do not decode. A settle payer flag is a bool, so 2 is not one.
        assertShownRaw(v4Input(byteArrayOf(0x0c, 0x0f), listOf(pairParam(USDC, BigInteger.TEN), ByteArray(40))))
        assertShownRaw(v4Input(byteArrayOf(0x0b), listOf(pairParam(USDC, BigInteger.TEN) + word(2L))))
    }

    @Test
    fun aCountMismatchBetweenActionsAndParamsFailsTheCommand() {
        assertShownRaw(v4Input(byteArrayOf(0x0c, 0x0f), listOf(pairParam(USDC, BigInteger.TEN))))
        assertShownRaw(v4Input(byteArrayOf(0x0c), listOf(pairParam(USDC, BigInteger.TEN), pairParam(USDC, BigInteger.TEN))))
    }

    @Test
    fun anEmptyListSaysTheRouterDoesNothing() {
        val steps = assertNotNull(stepsOfV4(v4Input(ByteArray(0), emptyList())))

        assertEquals(listOf("No actions: the router does nothing"), steps.lines)
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun aTakeToTheRouterLeavesTheOutputThereUntilAnotherStepTakesItOut() {
        fun leaves(actions: ByteArray, params: List<ByteArray>) = assertNotNull(stepsOfV4(v4Input(actions, params))).leavesInRouter
        val toRouter = takeParam(USDC, ADDRESS_THIS, BigInteger.TEN)

        assertTrue(leaves(byteArrayOf(0x0e), listOf(toRouter)))
        assertTrue(leaves(byteArrayOf(0x10), listOf(abiAddressWord(USDC) + abiAddressWord(ADDRESS_THIS) + abiWord(BigInteger.valueOf(50)))))
        // A take to the signer or the message sender, and a take-all, leave nothing.
        assertFalse(leaves(byteArrayOf(0x0e), listOf(takeParam(USDC, SIGNER, BigInteger.TEN))))
        assertFalse(leaves(byteArrayOf(0x0e), listOf(takeParam(USDC, MSG_SENDER, BigInteger.TEN))))
        assertFalse(leaves(byteArrayOf(0x0f), listOf(pairParam(USDC, BigInteger.ZERO))))
        // The router pays the whole of it into the pool manager.
        assertFalse(leaves(byteArrayOf(0x0e, 0x0b), listOf(toRouter, settleParam(USDC, CONTRACT_BALANCE, payerIsUser = false))))
        assertTrue(leaves(byteArrayOf(0x0e, 0x0b), listOf(toRouter, settleParam(USDC, CONTRACT_BALANCE, payerIsUser = true))))
    }

    @Test
    fun aSweepAfterAV4TakeToTheRouterTakesTheOutputOut() {
        val take = v4Input(byteArrayOf(0x0e), listOf(takeParam(USDC, ADDRESS_THIS, BigInteger.TEN)))
        val sweep = abiAddressWord(USDC) + abiAddressWord(SIGNER) + abiWord(BigInteger.ZERO)
        fun leaves(vararg inputs: ByteArray): Boolean = assertNotNull(
            CallSteps.describe(
                STEP_REGISTRY,
                1,
                UR_V2,
                EXECUTE,
                EXECUTE_SIGNATURE,
                SIGNER,
                BigInteger.ZERO,
                listOf(
                    DecodedParam(EXECUTE_PARAMS[0], AbiValue.Dynamic(if (inputs.size == 1) "10" else "1004")),
                    DecodedParam(EXECUTE_PARAMS[1], AbiValue.Items(inputs.map { AbiValue.Dynamic(it.toHex()) })),
                    DecodedParam(EXECUTE_PARAMS[2], AbiValue.Word("00".repeat(ABI_WORD))),
                ),
            ),
        ).leavesInRouter

        assertTrue(leaves(take))
        assertFalse(leaves(take, sweep))
    }

    @Test
    fun aTakeToTheRoutersOwnAddressLeavesTheOutputThereAndAnExactSettleFromTheRouterTakesItOut() {
        fun leaves(actions: ByteArray, params: List<ByteArray>) = assertNotNull(stepsOfV4(v4Input(actions, params))).leavesInRouter
        val toRouter = takeParam(USDC, UR_V2, BigInteger.TEN)

        assertTrue(leaves(byteArrayOf(0x0e), listOf(toRouter)))
        assertTrue(leaves(byteArrayOf(0x10), listOf(abiAddressWord(USDC) + abiAddressWord(UR_V2) + abiWord(BigInteger.valueOf(50)))))
        assertFalse(leaves(byteArrayOf(0x0e, 0x0b), listOf(toRouter, settleParam(USDC, CONTRACT_BALANCE, payerIsUser = false))))
    }
}
