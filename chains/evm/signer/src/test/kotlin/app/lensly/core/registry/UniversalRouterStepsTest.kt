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
import kotlin.test.assertFalse
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

/** Multicall3, and a lookalike of [SIGNER]: the same first and last four hex characters, so a shortened address reads as the signer. */
private const val MULTICALL3 = "0xca11bde05977b3631167028862be2a173976ca11"
private const val OTHER = "0x00000000000000000000000000000000000000aa"
private const val LOOKALIKE = "0x0bf9" + "00000000000000000000000000000000" + "777f"

/** The steps of a call built from its [commands] and their [inputs], for the cases a real router call does not show. [caller] calls the router. */
private fun stepsOfParts(contract: String, commands: ByteArray, inputs: List<ByteArray>, caller: String = SIGNER): CallSteps? = CallSteps.describe(
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
    caller,
)

/** The ABI encoding of V3_SWAP_EXACT_IN with [path] to [recipient], which is the input a router command holds. */
private fun v3ExactInInput(path: ByteArray, recipient: String = NATIVE_COIN, payerIsUser: Boolean = true): ByteArray {
    val head = abiAddressWord(recipient) + abiWord(BigInteger.TEN.pow(18)) + abiWord(BigInteger.ZERO) +
        abiWord(BigInteger.valueOf(5L * ABI_WORD)) + abiWord(if (payerIsUser) BigInteger.ONE else BigInteger.ZERO)
    val padded = path + ByteArray((ABI_WORD - path.size % ABI_WORD) % ABI_WORD)
    return head + abiWord(BigInteger.valueOf(path.size.toLong())) + padded
}

private const val FUZZ_ROUNDS = 3000

/** A WETH to USDC path through the 0.05% pool. */
private val WETH_TO_USDC = Numeric.hexStringToByteArray(WETH) + byteArrayOf(0, 1, 0xf4.toByte()) + Numeric.hexStringToByteArray(USDC)

private const val SWAP_WARNING = "This pays the swap output to "
private const val WRAP = 0x0b.toByte()
private const val V3_SWAP = 0x00.toByte()
private const val PERMIT2_TRANSFER_FROM = 0x02.toByte()
private const val SWEEP = 0x04.toByte()
private const val TRANSFER = 0x05.toByte()
private const val PAY_PORTION = 0x06.toByte()
private const val UNWRAP = 0x0c.toByte()

/** One router command that pays a recipient, and the input that names [input]'s recipient. */
private data class RouterStep(val name: String, val command: Byte, val input: (String) -> ByteArray)

/** The input of WRAP_ETH to [recipient]. */
private fun wrapInput(recipient: String): ByteArray = abiAddressWord(recipient) + abiWord(BigInteger.TEN.pow(18))

/** The input of a command that takes (token, recipient, amount). */
private fun tokenInput(recipient: String, amount: BigInteger = BigInteger.TEN): ByteArray =
    abiAddressWord(USDC) + abiAddressWord(recipient) + abiWord(amount)

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
    fun aV4SwapReadsEachActionAsAStep() {
        // Transaction 0x5ea7fc9a, on Universal Router V2 (block 26425582): Permit2 permit for the router, then the v4 swap at command 0x10,
        // which is one swap, one settle and one take.
        val steps = assertNotNull(stepsOf(StepVectors.UR_V2_V4, UR_V2))

        assertEquals(
            listOf(
                "Approve the Universal Router to spend 0xc8Fb…8888 amount 7756377918158211619 base units until 2026-11-16 05:46 UTC",
                "Swap exactly 7756377918158211619 base units of 0xc8Fb…8888 for ≥ 0.000736805214329485 ETH via Uniswap v4 (0%)",
                "Pay all 0xc8Fb…8888 the router owes from you to Uniswap v4 (at most 7756377918158211619 base units of 0xc8Fb…8888)",
                "Take all ETH owed to the router from Uniswap v4 to you",
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
    fun theAllowRevertFlagGetsNoNoteOnACommandThatRevertsTheBatchItself() {
        // 0x85 is TRANSFER (0x05) and 0x90 is V4_SWAP, each with the allow-revert flag. Both revert the batch on failure whatever the
        // flag, so neither gets a note. ETHG is unregistered, so its amount is raw.
        val transfer = abiAddressWord(ETHG) + abiAddressWord(SIGNER) + abiWord(BigInteger.valueOf(7L))

        val steps = assertNotNull(stepsOfParts(UR_V1_2, byteArrayOf(0x85.toByte()), listOf(transfer)))
        val v4 = assertNotNull(stepsOfParts(UR_V2, byteArrayOf(0x90.toByte()), listOf(ByteArray(0))))

        assertEquals(listOf("Transfer 7 base units of 0x3fC2…0EAD from the router to you"), steps.lines)
        // An empty v4 input does not decode, so the command is shown raw with a warning, and it gets no allow-revert note.
        assertEquals(listOf("Command 0x10 could not be read: raw input 0x"), v4.lines)
        assertEquals(listOf("Lensly cannot read the input of command 0x10. Its raw input is under Details."), v4.warnings)
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun theAllowRevertFlagIsSaidOnABalanceCheckOnBothDialects() {
        // 0x8e is BALANCE_CHECK_ERC20 with the allow-revert flag. A short balance returns success=false, so the batch goes on.
        val input = abiAddressWord(SIGNER) + abiAddressWord(ETHG) + abiWord(BigInteger.valueOf(7L))

        listOf(UR_V1_2, UR_V2).forEach { contract ->
            val steps = assertNotNull(stepsOfParts(contract, byteArrayOf(0x8e.toByte()), listOf(input)))

            assertEquals(
                listOf("Require you to hold at least 7 base units of 0x3fC2…0EAD (if it fails, the rest of the batch still runs)"),
                steps.lines,
            )
        }
    }

    @Test
    fun theAllowRevertFlagIsSaidOnAPermitOnlyWhereItIsACallToPermit2() {
        // 0x8a is PERMIT2_PERMIT with the allow-revert flag. V2 calls Permit2 with `.call`, so a failed permit returns success=false.
        // V1.2 calls Permit2 directly, so the permit reverts the batch and gets no note.
        val input = abiAddressWord(USDC) + abiWord(BigInteger.TEN.pow(6)) + abiWord(BigInteger.valueOf(1_800_000_000L)) +
            abiWord(BigInteger.ZERO) + abiAddressWord(UR_V2) + abiWord(BigInteger.ZERO) +
            abiWord(BigInteger.valueOf(7L * ABI_WORD)) + abiWord(BigInteger.ZERO)

        val v2 = assertNotNull(stepsOfParts(UR_V2, byteArrayOf(0x8a.toByte()), listOf(input))).lines.single()
        val v1 = assertNotNull(stepsOfParts(UR_V1_2, byteArrayOf(0x8a.toByte()), listOf(input))).lines.single()

        assertTrue(v2.endsWith("(if it fails, the rest of the batch still runs)"), v2)
        assertFalse(v1.contains("if it fails"), v1)
    }

    @Test
    fun aCommandLenslyDoesNotReadGetsNoNote() {
        // 0x91 is command 0x11, V3_POSITION_MANAGER_PERMIT, which Lensly does not read yet. Its step says nothing about the flag.
        val steps = assertNotNull(stepsOfParts(UR_V2, byteArrayOf(0x91.toByte()), listOf(byteArrayOf(0x11, 0x22))))

        assertEquals(listOf("Unknown command 0x11: raw input 0x1122"), steps.lines)
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
    fun aSwapInsideABatchPaysTheBatchContractWhenItsRecipientIsTheMessageSenderFlag() {
        // Multicall3 calls the router, so the router's message sender is Multicall3: WRAP_ETH to the router, then a swap of all of it
        // to the flag 0x...01 leaves the output with Multicall3.
        val commands = byteArrayOf(WRAP, V3_SWAP)
        val inputs = listOf(wrapInput(ADDRESS_THIS), v3ExactInInput(WETH_TO_USDC, MSG_SENDER, payerIsUser = false))

        val viaBatch = assertNotNull(stepsOfParts(UR_V2, commands, inputs, caller = MULTICALL3))
        val direct = assertNotNull(stepsOfParts(UR_V2, commands, inputs))

        assertEquals(
            listOf(
                "Wrap 1 ETH → WETH in the router",
                "Swap exactly 1 WETH for ≥ 0 USDC via Uniswap v3 (0.05%), sent to Multicall3",
            ),
            viaBatch.lines,
        )
        assertEquals(listOf("This pays the swap output to 0xcA11…CA11, not you"), viaBatch.warnings)
        assertEquals(listOf(MULTICALL3), viaBatch.addresses)
        // The same call from the signer pays the signer: no note, no warning.
        assertEquals(listOf("Wrap 1 ETH → WETH in the router", "Swap exactly 1 WETH for ≥ 0 USDC via Uniswap v3 (0.05%)"), direct.lines)
        assertEquals(emptyList(), direct.warnings)
        assertEquals(emptyList(), direct.addresses)
    }

    @Test
    fun aBatchReadsEveryOtherMessageSenderWordingAsTheBatchContract() {
        val permit = assertNotNull(
            stepsOfParts(UR_V2, byteArrayOf(PERMIT2_TRANSFER_FROM), listOf(tokenInput(MSG_SENDER, BigInteger.valueOf(10_000_000_000))), MULTICALL3),
        )
        assertEquals(listOf("Transfer 10,000 USDC from Multicall3 to Multicall3 via Permit2"), permit.lines)
        assertEquals(listOf(MULTICALL3), permit.addresses)

        val wrap = assertNotNull(stepsOfParts(UR_V2, byteArrayOf(WRAP), listOf(wrapInput(MSG_SENDER)), MULTICALL3))
        assertEquals(listOf("Wrap 1 ETH → WETH for Multicall3"), wrap.lines)
        assertEquals(listOf("This wraps ETH for 0xcA11…CA11, not you"), wrap.warnings)

        val unwrap = assertNotNull(stepsOfParts(UR_V2, byteArrayOf(UNWRAP), listOf(abiAddressWord(MSG_SENDER) + abiWord(BigInteger.ZERO)), MULTICALL3))
        assertEquals(listOf("Unwrap all WETH the router holds → ETH for Multicall3"), unwrap.lines)
        assertEquals(1, unwrap.warnings.size)

        val payerIsUser = assertNotNull(
            stepsOfParts(UR_V2, byteArrayOf(V3_SWAP), listOf(v3ExactInInput(WETH_TO_USDC, ADDRESS_THIS)), MULTICALL3),
        )
        assertTrue(payerIsUser.lines.single().endsWith(", paid by Multicall3, kept in the router"), payerIsUser.lines.single())
        assertEquals(emptyList(), payerIsUser.warnings)
    }

    @Test
    fun aSwapToALookalikeOfTheSignerWarnsAndNamesTheFullAddress() {
        val steps = assertNotNull(stepsOfParts(UR_V2, byteArrayOf(V3_SWAP), listOf(v3ExactInInput(WETH_TO_USDC, LOOKALIKE))))

        assertEquals(listOf(SWAP_WARNING + shortAddress(LOOKALIKE) + ", not you"), steps.warnings)
        assertEquals(listOf(LOOKALIKE), steps.addresses)
        assertTrue(steps.lines.single().endsWith(", sent to ${shortAddress(LOOKALIKE)}"), steps.lines.single())
    }

    @Test
    fun everyRouterStepThatPaysAnotherAddressWarnsAndEveryOneThatPaysTheSignerOrTheRouterDoesNot() {
        val steps = listOf(
            RouterStep("permit2 transfer", PERMIT2_TRANSFER_FROM) { tokenInput(it) },
            RouterStep("sweep", SWEEP) { tokenInput(it) },
            RouterStep("transfer", TRANSFER) { tokenInput(it) },
            RouterStep("pay portion", PAY_PORTION) { tokenInput(it, BigInteger.valueOf(50)) },
            RouterStep("v3 swap", V3_SWAP) { v3ExactInInput(WETH_TO_USDC, it) },
            RouterStep("wrap", WRAP) { wrapInput(it) },
            RouterStep("unwrap", UNWRAP) { abiAddressWord(it) + abiWord(BigInteger.ZERO) },
        )
        steps.forEach { (name, command, input) ->
            fun of(recipient: String) = assertNotNull(stepsOfParts(UR_V2, byteArrayOf(command), listOf(input(recipient))), name)

            listOf(OTHER, LOOKALIKE).forEach { other ->
                val paid = of(other)
                assertEquals(1, paid.warnings.size, "$name to $other")
                assertTrue(paid.warnings.single().endsWith("${shortAddress(other)}, not you"), paid.warnings.single())
                assertEquals(listOf(other), paid.addresses, name)
            }
            listOf(SIGNER, MSG_SENDER, ADDRESS_THIS).forEach { own ->
                assertEquals(emptyList(), of(own).warnings, "$name to $own")
                assertEquals(emptyList(), of(own).addresses, "$name to $own")
            }
        }
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
