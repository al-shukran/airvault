package app.lensly.core.registry

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.lensly.core.wallet.toHex
import org.junit.Test
import org.web3j.utils.Numeric

private const val AAVE_POOL = "0x87870bca3f3fd6335c3f4ce8392d69350b4fa4e2"
private const val CUSDC_V3 = "0xc3d688b66703497daa19211eedff47f25384cdc3"
private const val LIDO = "0xae7ab96520de3a18e5e111b5eaab095312d7fe84"
private const val WETH_CONTRACT = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2"
private const val STRANGER = "0x0000000000000000000000000000000000000abc"
private const val FUZZ_ROUNDS = 2000

private val AAVE_SUPPLY = listOf(
    WhitelistParam("asset", "address"),
    WhitelistParam("amount", "uint256"),
    WhitelistParam("onBehalfOf", "address"),
    WhitelistParam("referralCode", "uint16"),
)
private val AAVE_BORROW = listOf(
    WhitelistParam("asset", "address"),
    WhitelistParam("amount", "uint256"),
    WhitelistParam("interestRateMode", "uint256"),
    WhitelistParam("referralCode", "uint16"),
    WhitelistParam("onBehalfOf", "address"),
)
private val AAVE_REPAY = listOf(
    WhitelistParam("asset", "address"),
    WhitelistParam("amount", "uint256"),
    WhitelistParam("interestRateMode", "uint256"),
    WhitelistParam("onBehalfOf", "address"),
)
private val AAVE_WITHDRAW = listOf(
    WhitelistParam("asset", "address"),
    WhitelistParam("amount", "uint256"),
    WhitelistParam("to", "address"),
)
private val AAVE_COLLATERAL = listOf(WhitelistParam("asset", "address"), WhitelistParam("useAsCollateral", "bool"))
private val COMPOUND_ASSET_AMOUNT = listOf(WhitelistParam("asset", "address"), WhitelistParam("amount", "uint256"))
private val ONE_ADDRESS = listOf(WhitelistParam("_referral", "address"))
private val ONE_AMOUNT = listOf(WhitelistParam("wad", "uint256"))

/** The steps of a real [calldata] to [contract], decoded with [params] as the sandbox decodes an approved call. */
private fun stepsOf(
    calldata: String,
    contract: String,
    selector: String,
    signature: String,
    params: List<WhitelistParam>,
    from: String = SIGNER,
    sent: BigInteger = BigInteger.ZERO,
): CallSteps? {
    val decoded = (AbiDecoder.decode(Numeric.hexStringToByteArray(calldata), params) as CallDecode.Ok).params
    return CallSteps.describe(STEP_REGISTRY, 1, contract, selector, signature, from, sent, decoded)
}

/** The calldata of [selector] followed by [words], which are each one 32-byte argument. */
private fun call(selector: String, vararg words: ByteArray): String = selector + words.joinToString("") { it.toHex() }

class ProtocolStepsTest {

    @Test
    fun anAaveSupplyOnBehalfOfTheSignerNamesTheSigner() {
        // Transaction 0xc1d19a6b, Aave V3 Pool: supply 71.06853 USDC on behalf of the sender.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.AAVE_SUPPLY,
                AAVE_POOL,
                "0x617ba037",
                "supply(address,uint256,address,uint16)",
                AAVE_SUPPLY,
                from = "0x1593d3980018812a3f95290cf896437f3ea25343",
            ),
        )

        assertEquals(listOf("Supply 71.06853 USDC to Aave V3 on behalf of you"), steps.lines)
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun anAaveSupplyOnBehalfOfAnotherAddressShowsThatAddress() {
        val steps = assertNotNull(
            stepsOf(
                StepVectors.AAVE_SUPPLY,
                AAVE_POOL,
                "0x617ba037",
                "supply(address,uint256,address,uint16)",
                AAVE_SUPPLY,
                from = STRANGER,
            ),
        )

        assertEquals(listOf("Supply 71.06853 USDC to Aave V3 on behalf of 0x1593…5343"), steps.lines)
    }

    @Test
    fun anAaveBorrowAtTheVariableRateSaysSo() {
        // Transaction 0xcbc93ff7, Aave V3 Pool: borrow 390 USDC at interest rate mode 2 (variable).
        val steps = assertNotNull(
            stepsOf(
                StepVectors.AAVE_BORROW,
                AAVE_POOL,
                "0xa415bcad",
                "borrow(address,uint256,uint256,uint16,address)",
                AAVE_BORROW,
                from = "0x5762d4802afae5bbb0ab0d30aab2162863dba767",
            ),
        )

        assertEquals(listOf("Borrow 390 USDC from Aave V3 at a variable rate on behalf of you"), steps.lines)
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun anAaveBorrowInAnUnknownRateModeWarns() {
        val input = call(
            "0xa415bcad",
            abiAddressWord(USDC),
            abiWord(BigInteger.valueOf(1_000_000L)),
            abiWord(BigInteger.valueOf(3L)),
            abiWord(BigInteger.ZERO),
            abiAddressWord(SIGNER),
        )

        val steps = assertNotNull(
            stepsOf(input, AAVE_POOL, "0xa415bcad", "borrow(address,uint256,uint256,uint16,address)", AAVE_BORROW),
        )

        assertEquals(listOf("Borrow 1 USDC from Aave V3 at interest rate mode 3 on behalf of you"), steps.lines)
        assertEquals(listOf("Lensly does not know Aave interest rate mode 3, which the pool reverts"), steps.warnings)
    }

    @Test
    fun anAaveRepayAtTheVariableRateSaysSo() {
        // Transaction 0x2c70114d, Aave V3 Pool: repay 1,813.523297 USDC at interest rate mode 2 (variable).
        val steps = assertNotNull(
            stepsOf(
                StepVectors.AAVE_REPAY,
                AAVE_POOL,
                "0x573ade81",
                "repay(address,uint256,uint256,address)",
                AAVE_REPAY,
                from = "0x0018ea4bab28d0737a77164d2331790c49ce0734",
            ),
        )

        assertEquals(listOf("Repay 1,813.523297 USDC to Aave V3 at a variable rate on behalf of you"), steps.lines)
    }

    @Test
    fun anAaveWithdrawOfTheWholeBalanceSaysAll() {
        // Transaction 0x8f8f3fd1, Aave V3 Pool: withdraw the whole USDT balance, which is 2^256 - 1, to the sender.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.AAVE_WITHDRAW_ALL,
                AAVE_POOL,
                "0x69328dec",
                "withdraw(address,uint256,address)",
                AAVE_WITHDRAW,
                from = "0x90e80dd2c755f54a1bf9872d9e9291d5eda0471f",
            ),
        )

        assertEquals(listOf("Withdraw all USDT from Aave V3 to you"), steps.lines)
    }

    @Test
    fun anAaveCollateralSwitchNamesTheAsset() {
        // Transaction 0xdb88ebd4, Aave V3 Pool: use LINK as collateral.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.AAVE_COLLATERAL,
                AAVE_POOL,
                "0x5a3b74b9",
                "setUserUseReserveAsCollateral(address,bool)",
                AAVE_COLLATERAL,
            ),
        )

        assertEquals(listOf("Use LINK as collateral on Aave V3"), steps.lines)
    }

    @Test
    fun aCompoundSupplyOfTheBaseTokenNamesTheMarket() {
        // Transaction 0xeeb9f756, Compound V3 cUSDCv3: supply 3,468.61997 USDC, the base token.
        val steps = assertNotNull(
            stepsOf(StepVectors.COMPOUND_SUPPLY_USDC, CUSDC_V3, "0xf2b9fdb8", "supply(address,uint256)", COMPOUND_ASSET_AMOUNT),
        )

        assertEquals(listOf("Supply 3,468.61997 USDC to Compound V3 (cUSDCv3)"), steps.lines)
    }

    @Test
    fun aCompoundSupplyOfCollateralSaysItIsCollateral() {
        // Transaction 0xad9e991d, Compound V3 cUSDCv3: supply UNI, which is collateral in this market and not registered.
        val steps = assertNotNull(
            stepsOf(StepVectors.COMPOUND_SUPPLY_COLLATERAL, CUSDC_V3, "0xf2b9fdb8", "supply(address,uint256)", COMPOUND_ASSET_AMOUNT),
        )

        assertEquals(
            listOf("Supply 25113604000000000000 base units of 0x1f98…F984 as collateral to Compound V3 (cUSDCv3)"),
            steps.lines,
        )
    }

    @Test
    fun aCompoundWithdrawOfTheBaseTokenNamesTheMarket() {
        // Transaction 0x612e9b02, Compound V3 cUSDCv3: withdraw 1,100 USDC.
        val steps = assertNotNull(
            stepsOf(StepVectors.COMPOUND_WITHDRAW_USDC, CUSDC_V3, "0xf3fef3a3", "withdraw(address,uint256)", COMPOUND_ASSET_AMOUNT),
        )

        assertEquals(listOf("Withdraw 1,100 USDC from Compound V3 (cUSDCv3)"), steps.lines)
    }

    @Test
    fun aLidoStakeWithAReferralNamesIt() {
        // Transaction 0xacd74108, Lido: stake the 3 ETH the call sends, with a referral.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.LIDO_SUBMIT,
                LIDO,
                "0xa1903eab",
                "submit(address)",
                ONE_ADDRESS,
                sent = BigInteger("3000000000000000000"),
            ),
        )

        assertEquals(listOf("Stake 3 ETH with Lido for stETH (referral 0x11d0…11d0)"), steps.lines)
    }

    @Test
    fun aLidoStakeWithoutAReferralSaysOnlyTheAmount() {
        // Transaction 0xb9b524b5, Lido: stake 0.00001074 ETH with the zero referral.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.LIDO_SUBMIT_NO_REFERRAL,
                LIDO,
                "0xa1903eab",
                "submit(address)",
                ONE_ADDRESS,
                sent = BigInteger("10740000000000"),
            ),
        )

        assertEquals(listOf("Stake 0.00001074 ETH with Lido for stETH"), steps.lines)
    }

    @Test
    fun aWethDepositWrapsTheSentEth() {
        // Transaction 0x2aa8cc21, WETH: deposit 2.19294275140625435 ETH.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.WETH_DEPOSIT,
                WETH_CONTRACT,
                "0xd0e30db0",
                "deposit()",
                emptyList(),
                sent = BigInteger("2192942751406254350"),
            ),
        )

        assertEquals(listOf("Wrap 2.19294275140625435 ETH → WETH"), steps.lines)
    }

    @Test
    fun aWethWithdrawUnwrapsTheAmount() {
        // Transaction 0x6115492e, WETH: withdraw 0.055 WETH.
        val steps = assertNotNull(stepsOf(StepVectors.WETH_WITHDRAW, WETH_CONTRACT, "0x2e1a7d4d", "withdraw(uint256)", ONE_AMOUNT))

        assertEquals(listOf("Unwrap 0.055 WETH → ETH"), steps.lines)
    }

    @Test
    fun aCallWithAnotherSignatureOrNetworkIsNotDescribed() {
        assertNull(CallSteps.describe(STEP_REGISTRY, 1, AAVE_POOL, "0x617ba037", "supply(address,uint256)", SIGNER, BigInteger.ZERO, emptyList()))
        assertNull(CallSteps.describe(STEP_REGISTRY, 10, AAVE_POOL, "0x617ba037", "supply(address,uint256,address,uint16)", SIGNER, BigInteger.ZERO, emptyList()))
        assertNull(CallSteps.describe(STEP_REGISTRY, 1, AAVE_POOL, "0x00000000", "unknown()", SIGNER, BigInteger.ZERO, emptyList()))
    }

    @Test
    fun randomArgumentsNeverThrowForAnAaveSupply() {
        val random = Random(20261116)
        repeat(FUZZ_ROUNDS) {
            val values = List(AAVE_SUPPLY.size) { index ->
                val hex = (0 until WORD_BYTES).joinToString("") { "%02x".format(random.nextInt(0, 256)) }
                DecodedParam(AAVE_SUPPLY[index], AbiValue.Word(hex))
            }

            val steps = assertNotNull(
                CallSteps.describe(STEP_REGISTRY, 1, AAVE_POOL, "0x617ba037", "supply(address,uint256,address,uint16)", SIGNER, BigInteger.ZERO, values),
            )

            assertEquals(1, steps.lines.size)
            assertTrue(steps.lines.single().startsWith("Supply "), steps.lines.single())
        }
    }

    private companion object {
        const val WORD_BYTES = 32
    }
}
