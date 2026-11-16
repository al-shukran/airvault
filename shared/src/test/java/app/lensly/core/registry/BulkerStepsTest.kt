package app.lensly.core.registry

import app.lensly.core.wallet.toHex
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric

private const val BULKER = "0xa397a8c2086c554b531c02e29f3291c9704b00c7"
private const val BULKER_SELECTOR = "0x555029a6"
private const val BULKER_SIGNATURE = "invoke(bytes32[],bytes[])"
private const val FUZZ_ROUNDS = 2000
private const val WORD = 32

/** The arguments of `invoke(bytes32[],bytes[])`, in the order the approved entry names them. */
private val BULKER_PARAMS = listOf(WhitelistParam("actions", "bytes32[]"), WhitelistParam("data", "bytes[]"))

/** The steps of a real bulker [calldata] sent by [from] with [sent] native coin, decoded as the sandbox decodes an approved call. */
private fun stepsOf(calldata: String, from: String, sent: BigInteger = BigInteger.ZERO): CallSteps? {
    val decoded = (AbiDecoder.decode(Numeric.hexStringToByteArray(calldata), BULKER_PARAMS) as CallDecode.Ok).params
    return CallSteps.describe(STEP_REGISTRY, 1, BULKER, BULKER_SELECTOR, BULKER_SIGNATURE, from, sent, decoded)
}

class BulkerStepsTest {

    @Test
    fun aNativeSupplyToCWethNamesTheAmountAndTheSigner() {
        // Transaction 0x0384c7e9, Compound V3 bulker: ACTION_SUPPLY_NATIVE_TOKEN of 0.114 ETH to cWETHv3 for the sender.
        val steps = assertNotNull(
            stepsOf(StepVectors.BULKER_SUPPLY_NATIVE, "0x9ff44cbe82dbd26c83397cd2e674c373ebcbb058", BigInteger("114000000000000000")),
        )

        assertEquals(listOf("Supply 0.114 ETH to Compound V3 (cWETHv3) for you"), steps.lines)
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun aSupplyThenAWithdrawInOneBatchReadsEachActionInOrder() {
        // Transaction 0x10fdda80, Compound V3 bulker: ACTION_SUPPLY_ASSET of 91,612.150292 USDC on cWETHv3, then ACTION_WITHDRAW_NATIVE_TOKEN of 26 ETH.
        val steps = assertNotNull(stepsOf(StepVectors.BULKER_SUPPLY_ASSET_WITHDRAW_NATIVE, "0xccaa76f69f3a3f75a8b60600bf16443c1c4614e7"))

        assertEquals(
            listOf(
                "Supply 91,612.150292 USDC as collateral to Compound V3 (cWETHv3) for you",
                "Withdraw 26 ETH from Compound V3 (cWETHv3) to you",
            ),
            steps.lines,
        )
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun anActionLenslyDoesNotReadIsShownRawAndWarns() {
        // Transaction 0x5ab615b0, Compound V3 bulker: ACTION_SUPPLY_STETH, which is not a base bulker action, then a native withdrawal.
        val steps = assertNotNull(stepsOf(StepVectors.BULKER_SUPPLY_STETH, "0xe6705fccaf951870ef24463c88a81f31610efba4"))

        assertTrue(steps.lines.first().startsWith("Unknown bulker action ACTION_SUPPLY_STETH: raw input 0x"), steps.lines.first())
        assertTrue(
            steps.warnings.contains("Lensly cannot read bulker action ACTION_SUPPLY_STETH. Its raw input is under Details."),
            steps.warnings.toString(),
        )
    }

    @Test
    fun randomBatchesNeverThrowAndAnUnreadActionAlwaysWarns() {
        val names = listOf("ACTION_SUPPLY_ASSET", "ACTION_SUPPLY_NATIVE_TOKEN", "ACTION_WITHDRAW_NATIVE_TOKEN", "ACTION_CLAIM_REWARD")
        val random = Random(20261116)
        repeat(FUZZ_ROUNDS) {
            val count = random.nextInt(0, 4)
            val words = List(count) {
                if (random.nextBoolean()) {
                    names.random(random).toByteArray(Charsets.US_ASCII).copyOf(WORD).toHex()
                } else {
                    ByteArray(WORD) { random.nextInt(0, 256).toByte() }.toHex()
                }
            }
            val data = List(count) { ByteArray(random.nextInt(0, 200)) { random.nextInt(0, 256).toByte() } }
            val params = listOf(
                DecodedParam(BULKER_PARAMS[0], AbiValue.Items(words.map { AbiValue.Word(it) })),
                DecodedParam(BULKER_PARAMS[1], AbiValue.Items(data.map { AbiValue.Dynamic(it.toHex()) })),
            )

            val steps = assertNotNull(
                CallSteps.describe(STEP_REGISTRY, 1, BULKER, BULKER_SELECTOR, BULKER_SIGNATURE, SIGNER, BigInteger.ZERO, params),
            )

            val unread = steps.lines.any { it.startsWith("Unknown bulker action") || it.contains("could not be read") }
            if (unread) assertTrue(steps.warnings.isNotEmpty(), steps.lines.toString())
            assertTrue(steps.lines.isNotEmpty() || steps.warnings.isNotEmpty())
        }
    }
}
