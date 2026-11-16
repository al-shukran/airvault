package app.lensly.core.registry

import app.lensly.core.wallet.toHex
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric

private const val UR_V1_2 = "0x3fc91a3afd70395cd496c647d5a6cc9d4b2b7fad"
private const val UR_V2 = "0x66a9893cc07d91d95644aedd05d03f95e1dba8af"
private const val EXECUTE = "0x3593564c"
private const val EXECUTE_SIGNATURE = "execute(bytes,bytes[],uint256)"

/** The arguments of `execute(bytes,bytes[],uint256)`, in the order the approved entry names them. */
private val EXECUTE_PARAMS = listOf(
    WhitelistParam("commands", "bytes"),
    WhitelistParam("inputs", "bytes[]"),
    WhitelistParam("deadline", "uint256"),
)

/** The steps of a real [calldata] sent to [contract] by [from], decoded as the sandbox decodes an approved call. */
private fun stepsOf(calldata: String, contract: String, from: String = SIGNER): CallSteps? {
    val decoded = (AbiDecoder.decode(Numeric.hexStringToByteArray(calldata), EXECUTE_PARAMS) as CallDecode.Ok).params
    return CallSteps.describe(STEP_REGISTRY, 1, contract, EXECUTE, EXECUTE_SIGNATURE, from, BigInteger.ZERO, decoded)
}

/** The steps of a call built from its [commands] and their [inputs], for the cases a real router call does not show. */
private fun stepsOfParts(contract: String, commands: ByteArray, inputs: List<ByteArray>): CallSteps? = CallSteps.describe(
    STEP_REGISTRY,
    1,
    contract,
    EXECUTE,
    EXECUTE_SIGNATURE,
    SIGNER,
    BigInteger.ZERO,
    listOf(
        DecodedParam(EXECUTE_PARAMS[0], AbiValue.Dynamic(commands.toHex())),
        DecodedParam(EXECUTE_PARAMS[1], AbiValue.Items(inputs.map { AbiValue.Dynamic(it.toHex()) })),
        DecodedParam(EXECUTE_PARAMS[2], AbiValue.Word("00".repeat(ABI_WORD))),
    ),
)

/** The ABI encoding of V3_SWAP_EXACT_IN with [path], which is the input a router command holds. */
private fun v3ExactInInput(path: ByteArray): ByteArray {
    val head = abiWord(BigInteger.ZERO) + abiWord(BigInteger.TEN.pow(18)) + abiWord(BigInteger.ZERO) +
        abiWord(BigInteger.valueOf(5L * ABI_WORD)) + abiWord(BigInteger.ONE)
    val padded = path + ByteArray((ABI_WORD - path.size % ABI_WORD) % ABI_WORD)
    return head + abiWord(BigInteger.valueOf(path.size.toLong())) + padded
}

private const val FUZZ_ROUNDS = 3000

class UniversalRouterStepsTest {

    @Test
    fun aMultiStepSwapWithAPermitIsReadStepByStepAndAnUnlimitedApprovalWarns() {
        // Transaction 0x3bb82bad, on Universal Router V1.2: Permit2 permit of USDC, V3 swap of 10,000 USDC to WETH, unwrap.
        val steps = assertNotNull(stepsOf(StepVectors.UR_MULTI_STEP, UR_V1_2))

        assertEquals(
            listOf(
                "Approve the Universal Router to spend USDC amount unlimited until 2026-11-29 00:27 UTC",
                "Swap exactly 10,000 USDC for ≥ 4.491011881166149117 WETH via Uniswap v3 (0.05%), paid by you, kept in the router",
                "Unwrap all WETH the router holds → ETH for you (at least 4.491011881166149117 WETH)",
            ),
            steps.lines,
        )
        assertEquals(
            listOf("Unlimited approval: the Universal Router can spend all of your USDC until 2026-11-29 00:27 UTC"),
            steps.warnings,
        )
    }

    @Test
    fun aV2SwapOfAnUnregisteredTokenShowsItsRawAmountAndItsAddress() {
        // Transaction 0x0b4a73b4, on Universal Router V1.2: V2 swap of AGI to WETH, then unwrap. AGI is not registered.
        val steps = assertNotNull(stepsOf(StepVectors.UR_V2_SWAP, UR_V1_2, from = "0x7095688dbbe3dd2649cc4ddd77d5be84e97d4c54"))

        assertEquals(
            listOf(
                "Swap exactly 110000000000000000000000 base units of 0xBF01…a461 for ≥ 0.007797477281397367 WETH via Uniswap v2, " +
                    "paid by you, kept in the router",
                "Unwrap all WETH the router holds → ETH for you (at least 0.007797477281397367 WETH)",
            ),
            steps.lines,
        )
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun aWrapSwapAndSweepNameTheRouterAndTheSigner() {
        // Transaction 0xdc758387, on Universal Router V1.2: wrap ETH, V2 swap of WETH to EFUN paid by the router, sweep EFUN.
        val steps = assertNotNull(stepsOf(StepVectors.UR_WRAP_V2_SWEEP, UR_V1_2, from = "0x1753603034a750ec6c42565ce4c6ae88d8bcd706"))

        assertEquals(
            listOf(
                "Wrap 0.008456 ETH → WETH in the router",
                "Swap exactly 0.008456 WETH for ≥ 79728190054265695 base units of 0xbe7f…a938 via Uniswap v2, kept in the router",
                "Sweep all 0xbe7f…a938 held by the router to you (at least 79728190054265695 base units of 0xbe7f…a938)",
            ),
            steps.lines,
        )
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun aV4SwapIsNamedAndItsActionsStayRawUnderDetails() {
        // Transaction 0x5ea7fc9a, on Universal Router V2: Permit2 permit for the router, then the v4 swap at command 0x10.
        val steps = assertNotNull(stepsOf(StepVectors.UR_V2_V4, UR_V2))

        assertEquals(
            listOf(
                "Approve the Universal Router to spend 0xc8Fb…8888 amount 7756377918158211619 base units until 2026-11-16 05:46 UTC",
                "Uniswap v4 swap: its actions are shown as raw bytes under Details",
            ),
            steps.lines,
        )
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun commandTenIsAV4SwapOnV2ButMarketplaceOnV1_2AndWarnsThere() {
        // The same calldata, read as if it went to V1.2, where command 0x10 is a marketplace command Lensly does not read.
        val steps = assertNotNull(stepsOf(StepVectors.UR_V2_V4, UR_V1_2))

        assertEquals(
            "Approve 0x66a9…A8Af to spend 0xc8Fb…8888 amount 7756377918158211619 base units until 2026-11-16 05:46 UTC",
            steps.lines[0],
        )
        assertTrue(steps.lines[1].startsWith("Unknown command 0x10: raw input 0x"), steps.lines[1])
        assertEquals(listOf("Lensly cannot read command 0x10 in this router call. Its raw input is under Details."), steps.warnings)
    }

    @Test
    fun anUnknownCommandIsShownWithItsRawInputAndWarns() {
        val steps = assertNotNull(stepsOfParts(UR_V1_2, byteArrayOf(0x07), listOf(byteArrayOf(0x11, 0x22))))

        assertEquals(listOf("Unknown command 0x07: raw input 0x1122"), steps.lines)
        assertEquals(listOf("Lensly cannot read command 0x07 in this router call. Its raw input is under Details."), steps.warnings)
    }

    @Test
    fun theAllowRevertFlagIsSaidOnTheStepAndTheCommandIsReadWithoutIt() {
        // 0x85 is TRANSFER (0x05) with the allow-revert flag. ETHG is unregistered, so its amount is raw.
        val input = abiAddressWord(ETHG) + abiAddressWord(SIGNER) + abiWord(BigInteger.valueOf(7L))

        val steps = assertNotNull(stepsOfParts(UR_V1_2, byteArrayOf(0x85.toByte()), listOf(input)))

        assertEquals(
            listOf("Transfer 7 base units of 0x3fC2…0EAD from the router to you (if it fails, the rest of the batch still runs)"),
            steps.lines,
        )
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun aCountMismatchWarnsAndShowsNoSteps() {
        val steps = assertNotNull(stepsOfParts(UR_V1_2, byteArrayOf(0x00, 0x04), listOf(ByteArray(0))))

        assertEquals(emptyList(), steps.lines)
        assertEquals(listOf("The router call has 2 commands and 1 inputs, so the router would revert"), steps.warnings)
    }

    @Test
    fun anEmptyBatchSaysItDoesNothing() {
        val steps = assertNotNull(stepsOfParts(UR_V1_2, ByteArray(0), emptyList()))

        assertEquals(listOf("No commands: the router does nothing"), steps.lines)
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun aV3PathThatIsNotWholePoolsIsNotReadAndWarns() {
        // A token, a fee, and a token of 19 bytes: 42 bytes, which is not a whole number of pools.
        val path = Numeric.hexStringToByteArray(USDC) + byteArrayOf(0, 0, 5) + Numeric.hexStringToByteArray(WETH).copyOf(19)
        val steps = assertNotNull(stepsOfParts(UR_V1_2, byteArrayOf(0x00), listOf(v3ExactInInput(path))))

        assertTrue(steps.lines.single().startsWith("Command 0x00 could not be read: raw input 0x"), steps.lines.single())
        assertEquals(listOf("Lensly cannot read the input of command 0x00. Its raw input is under Details."), steps.warnings)
    }

    @Test
    fun aV2PathWithOneTokenIsNotReadAndWarns() {
        // V2_SWAP_EXACT_IN whose path is a single address, which the router reverts on.
        val input = abiWord(BigInteger.ZERO) + abiWord(BigInteger.TEN) + abiWord(BigInteger.ZERO) +
            abiWord(BigInteger.valueOf(5L * ABI_WORD)) + abiWord(BigInteger.ZERO) + abiWord(BigInteger.ONE) + abiWord(BigInteger.ONE)

        val steps = assertNotNull(stepsOfParts(UR_V1_2, byteArrayOf(0x08), listOf(input)))

        assertTrue(steps.lines.single().startsWith("Command 0x08 could not be read: raw input 0x"), steps.lines.single())
        assertEquals(1, steps.warnings.size)
    }

    @Test
    fun aCallThatIsNotAnApprovedRouterExecuteIsNotDescribed() {
        val empty = emptyList<DecodedParam>()
        assertNull(CallSteps.describe(STEP_REGISTRY, 1, "0x1111111111111111111111111111111111111111", EXECUTE, EXECUTE_SIGNATURE, SIGNER, BigInteger.ZERO, empty))
        assertNull(CallSteps.describe(STEP_REGISTRY, 1, UR_V1_2, "0x24856bc3", "execute(bytes,bytes[])", SIGNER, BigInteger.ZERO, empty))
        assertNull(CallSteps.describe(STEP_REGISTRY, 56, UR_V1_2, EXECUTE, EXECUTE_SIGNATURE, SIGNER, BigInteger.ZERO, empty))
    }

    @Test
    fun randomCommandsAndInputsNeverThrowAndAnUnreadStepAlwaysWarns() {
        val random = Random(20261116)
        repeat(FUZZ_ROUNDS) {
            val count = random.nextInt(0, 12)
            val commands = ByteArray(count) { random.nextInt(0, 256).toByte() }
            val inputs = List(count) { ByteArray(random.nextInt(0, 400)) { random.nextInt(0, 256).toByte() } }
            val contract = if (random.nextBoolean()) UR_V1_2 else UR_V2

            val steps = assertNotNull(stepsOfParts(contract, commands, inputs))

            val unread = steps.lines.any { it.startsWith("Unknown command") || it.contains("could not be read") }
            if (unread) assertTrue(steps.warnings.isNotEmpty(), steps.lines.toString())
            assertTrue(steps.lines.isNotEmpty() || steps.warnings.isNotEmpty())
        }
    }
}
