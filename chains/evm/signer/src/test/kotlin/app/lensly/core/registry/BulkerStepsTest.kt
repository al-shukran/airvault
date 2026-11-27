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
    fun aWithdrawOfAnAssetFromAMarketNamesTheMarketTheAmountAndTheSigner() {
        // Transaction 0xdc8c9f32: supplies 0.24552811 WBTC to cUSDCv3, then withdraws 5,399 USDC from it to the sender.
        val steps = assertNotNull(stepsOf(StepVectors.BULKER_SUPPLY_ASSET_WITHDRAW_ASSET, "0x1801c32e5fa1f52fb6f387c4c9fb2f03488024b5"))

        assertEquals(
            listOf(
                "Supply 0.24552811 WBTC as collateral to Compound V3 (cUSDCv3) for you",
                "Withdraw 5,399 USDC from Compound V3 (cUSDCv3) to you",
            ),
            steps.lines,
        )
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun aWithdrawToAnotherAccountWarns() {
        // The same transaction read for another signer, so its withdrawal pays an account that is not the signer.
        val steps = assertNotNull(stepsOf(StepVectors.BULKER_SUPPLY_ASSET_WITHDRAW_ASSET, "0x0000000000000000000000000000000000000abc"))

        assertEquals(
            listOf(
                "Supply 0.24552811 WBTC as collateral to Compound V3 (cUSDCv3) for 0x1801…24B5",
                "Withdraw 5,399 USDC from Compound V3 (cUSDCv3) to 0x1801…24B5",
            ),
            steps.lines,
        )
        assertEquals(listOf("This supplies to 0x1801…24B5, not you", "This withdraws to 0x1801…24B5, not you"), steps.warnings)
    }

    @Test
    fun aNativeSupplyForAnotherAccountWarns() {
        val steps = assertNotNull(stepsOf(StepVectors.BULKER_SUPPLY_NATIVE, "0x0000000000000000000000000000000000000abc", BigInteger("114000000000000000")))

        assertEquals(listOf("Supply 0.114 ETH to Compound V3 (cWETHv3) for 0x9ff4…B058"), steps.lines)
        assertEquals(listOf("This supplies to 0x9ff4…B058, not you"), steps.warnings)
    }

    @Test
    fun aWholeAmountNativeSupplyForAnotherAccountStillWarns() {
        val action = "ACTION_SUPPLY_NATIVE_TOKEN".toByteArray(Charsets.US_ASCII).copyOf(WORD).toHex()
        val market = "a17581a9e3356d9a858b789d68b4d866e593ae94".padStart(WORD * 2, '0')
        val to = "9ff44cbe82dbd26c83397cd2e674c373ebcbb058".padStart(WORD * 2, '0')
        val params = listOf(
            DecodedParam(BULKER_PARAMS[0], AbiValue.Items(listOf(AbiValue.Word(action)))),
            DecodedParam(BULKER_PARAMS[1], AbiValue.Items(listOf(AbiValue.Dynamic(market + to + "ff".repeat(WORD))))),
        )

        val steps = assertNotNull(
            CallSteps.describe(STEP_REGISTRY, 1, BULKER, BULKER_SELECTOR, BULKER_SIGNATURE, SIGNER, BigInteger.ZERO, params),
        )

        assertEquals(listOf("Supply the whole value to Compound V3 (cWETHv3) for 0x9ff4…B058 (the exact amount is not read)"), steps.lines)
        assertEquals(listOf("This supplies to 0x9ff4…B058, not you"), steps.warnings)
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
    fun randomWithdrawAssetArgumentsAreReadOrWarnAndNeverThrow() {
        val random = Random(20261119)
        val name = "ACTION_WITHDRAW_ASSET".toByteArray(Charsets.US_ASCII).copyOf(WORD).toHex()
        val markets = listOf(
            "c3d688b66703497daa19211eedff47f25384cdc3",
            "a17581a9e3356d9a858b789d68b4d866e593ae94",
            "3afdc9bca9213a35503b077a6072f3d0d5ab0840",
        )
        repeat(FUZZ_ROUNDS) {
            // The market word is a known market half the time, so the withdrawal is read. The other words are random.
            val market = if (random.nextBoolean()) markets[random.nextInt(markets.size)] else random.nextBytes(20).toHex()
            val data = market.padStart(WORD * 2, '0') + List(3) { random.nextBytes(WORD).toHex() }.joinToString("")
            val params = listOf(
                DecodedParam(BULKER_PARAMS[0], AbiValue.Items(listOf(AbiValue.Word(name)))),
                DecodedParam(BULKER_PARAMS[1], AbiValue.Items(listOf(AbiValue.Dynamic(data)))),
            )

            val steps = assertNotNull(
                CallSteps.describe(STEP_REGISTRY, 1, BULKER, BULKER_SELECTOR, BULKER_SIGNATURE, SIGNER, BigInteger.ZERO, params),
            )
            assertTrue(steps.lines.isNotEmpty() || steps.warnings.isNotEmpty(), steps.lines.toString())
        }
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
