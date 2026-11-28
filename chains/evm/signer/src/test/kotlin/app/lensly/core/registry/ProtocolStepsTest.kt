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

private const val AAVE_POOL = "0x87870bca3f3fd6335c3f4ce8392d69350b4fa4e2"
private const val CUSDC_V3 = "0xc3d688b66703497daa19211eedff47f25384cdc3"
private const val LIDO = "0xae7ab96520de3a18e5e111b5eaab095312d7fe84"
private const val WETH_CONTRACT = "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2"
private const val CWETH_V3 = "0xa17581a9e3356d9a858b789d68b4d866e593ae94"
private const val WSTETH = "0x7f39c581f595b53c5cb19bd0b3f8da6c935e2ca0"
private const val LIDO_QUEUE = "0x889edc2edab5f40e902b864ad4d7ade8e412f9b1"
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
private val COMPOUND_SUPPLY_TO = listOf(
    WhitelistParam("dst", "address"),
    WhitelistParam("asset", "address"),
    WhitelistParam("amount", "uint256"),
)
private val COMPOUND_WITHDRAW_FROM = listOf(
    WhitelistParam("src", "address"),
    WhitelistParam("to", "address"),
    WhitelistParam("asset", "address"),
    WhitelistParam("amount", "uint256"),
)
private const val SIGNER_SUPPLY_TO = "0x399ee7da8beeacf852a4f578de2cb340abddbdb4"
private const val SIGNER_WITHDRAW_FROM = "0x6567ce7829a9d7037722b1aca8b50fab4716d58f"
private val ONE_ADDRESS = listOf(WhitelistParam("_referral", "address"))
private val ONE_AMOUNT = listOf(WhitelistParam("wad", "uint256"))
private const val AAVE_GATEWAY = "0xd01607c3c5ecaba394d8be377a08590149325722"
private val AAVE_GATEWAY_DEPOSIT = listOf(
    WhitelistParam("unused", "address"),
    WhitelistParam("onBehalfOf", "address"),
    WhitelistParam("referralCode", "uint16"),
)
private val AAVE_GATEWAY_WITHDRAW = listOf(
    WhitelistParam("unused", "address"),
    WhitelistParam("amount", "uint256"),
    WhitelistParam("to", "address"),
)
private val AAVE_GATEWAY_BORROW = listOf(
    WhitelistParam("unused", "address"),
    WhitelistParam("amount", "uint256"),
    WhitelistParam("referralCode", "uint16"),
)
private val AAVE_GATEWAY_REPAY = listOf(
    WhitelistParam("unused", "address"),
    WhitelistParam("amount", "uint256"),
    WhitelistParam("onBehalfOf", "address"),
)
private val AAVE_REPAY_WITH_ATOKENS = listOf(
    WhitelistParam("asset", "address"),
    WhitelistParam("amount", "uint256"),
    WhitelistParam("interestRateMode", "uint256"),
)
private val AAVE_REPAY_WITH_PERMIT = listOf(
    WhitelistParam("asset", "address"),
    WhitelistParam("amount", "uint256"),
    WhitelistParam("interestRateMode", "uint256"),
    WhitelistParam("onBehalfOf", "address"),
    WhitelistParam("deadline", "uint256"),
    WhitelistParam("permitV", "uint8"),
    WhitelistParam("permitR", "bytes32"),
    WhitelistParam("permitS", "bytes32"),
)

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

        assertEquals(listOf("Supply 71.06853 USDC to Aave V3 on behalf of 0x1593 D398 … 3eA2 5343"), steps.lines)
        assertEquals(listOf("This supplies to 0x1593 D398 … 3eA2 5343, not you"), steps.warnings)
        assertAddressesInGroupsOfFour(steps)
    }

    /**
     * Every Aave step that pays or credits an address names it in the line and warns when it is not the signer, and warns of nothing when
     * it is. A lookalike of an address the owner knows must not pass with the line alone.
     */
    @Test
    fun anAaveStepThatPaysOrCreditsAnotherAddressWarnsAndOneForTheSignerDoesNot() {
        val other = "0x1593d3980018812a3f95290cf896437f3ea25343"
        val zero = abiWord(BigInteger.ZERO)
        val amount = abiWord(BigInteger.valueOf(1_000_000L))
        val asset = abiAddressWord(USDC)
        val account = abiAddressWord(other)
        val supplyPermitParams = AAVE_SUPPLY + listOf(
            WhitelistParam("deadline", "uint256"),
            WhitelistParam("permitV", "uint8"),
            WhitelistParam("permitR", "bytes32"),
            WhitelistParam("permitS", "bytes32"),
        )
        // The function, the contract, its calldata and params, and the warning it earns for [other].
        class Case(val signature: String, val selector: String, val contract: String, val params: List<WhitelistParam>, val input: String, val warning: String)
        val cases = listOf(
            Case("supply(address,uint256,address,uint16)", "0x617ba037", AAVE_POOL, AAVE_SUPPLY, call("0x617ba037", asset, amount, account, zero), "This supplies to 0x1593 D398 … 3eA2 5343, not you"),
            Case("withdraw(address,uint256,address)", "0x69328dec", AAVE_POOL, AAVE_WITHDRAW, call("0x69328dec", asset, amount, account), "This withdraws to 0x1593 D398 … 3eA2 5343, not you"),
            Case("repay(address,uint256,uint256,address)", "0x573ade81", AAVE_POOL, AAVE_REPAY, call("0x573ade81", asset, amount, abiWord(BigInteger.valueOf(2)), account), "This repays the debt of 0x1593 D398 … 3eA2 5343, not you"),
            Case(
                "supplyWithPermit(address,uint256,address,uint16,uint256,uint8,bytes32,bytes32)",
                "0x02c205f0",
                AAVE_POOL,
                supplyPermitParams,
                call("0x02c205f0", asset, amount, account, zero, zero, zero, zero, zero),
                "This supplies to 0x1593 D398 … 3eA2 5343, not you",
            ),
            Case(
                "repayWithPermit(address,uint256,uint256,address,uint256,uint8,bytes32,bytes32)",
                "0xee3e210b",
                AAVE_POOL,
                AAVE_REPAY_WITH_PERMIT,
                call("0xee3e210b", asset, amount, abiWord(BigInteger.valueOf(2)), account, zero, zero, zero, zero),
                "This repays the debt of 0x1593 D398 … 3eA2 5343, not you",
            ),
            Case("depositETH(address,address,uint16)", "0x474cf53d", AAVE_GATEWAY, AAVE_GATEWAY_DEPOSIT, call("0x474cf53d", zero, account, zero), "This supplies to 0x1593 D398 … 3eA2 5343, not you"),
            Case("withdrawETH(address,uint256,address)", "0x80500d20", AAVE_GATEWAY, AAVE_GATEWAY_WITHDRAW, call("0x80500d20", zero, amount, account), "This withdraws to 0x1593 D398 … 3eA2 5343, not you"),
            Case("repayETH(address,uint256,address)", "0xbcc3c255", AAVE_GATEWAY, AAVE_GATEWAY_REPAY, call("0xbcc3c255", zero, amount, account), "This repays the debt of 0x1593 D398 … 3eA2 5343, not you"),
        )

        for (case in cases) {
            val another = assertNotNull(stepsOf(case.input, case.contract, case.selector, case.signature, case.params, from = SIGNER), case.signature)
            val own = assertNotNull(stepsOf(case.input, case.contract, case.selector, case.signature, case.params, from = other), case.signature)

            assertTrue(case.warning in another.warnings, "${case.signature}: ${another.warnings}")
            assertTrue(own.warnings.none { it.endsWith("not you") }, "${case.signature}: ${own.warnings}")
        }
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
            listOf("Supply 25113604000000000000 base units of 0x1f98 40a8 … 4201 F984 as collateral to Compound V3 (cUSDCv3)"),
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
    fun aCompoundSupplyToAnotherAccountNamesTheAccountAndWarns() {
        // Transaction 0xd5d49512: supplies 9,301 USDC to the base market, credited to 0xC5Cd 3aBe … 3239 Cc46, which is not the signer.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.COMPOUND_SUPPLY_TO_USDC,
                CUSDC_V3,
                "0x4232cd63",
                "supplyTo(address,address,uint256)",
                COMPOUND_SUPPLY_TO,
                SIGNER_SUPPLY_TO,
            ),
        )

        assertEquals(listOf("Supply 9,301 USDC to Compound V3 (cUSDCv3) for 0xC5Cd 3aBe … 3239 Cc46"), steps.lines)
        assertEquals(listOf("This supplies to 0xC5Cd 3aBe … 3239 Cc46, not you"), steps.warnings)
    }

    @Test
    fun aCompoundWithdrawFromAnotherAccountNamesBothAccountsAndWarns() {
        // Transaction 0x4680103d: withdraws all USDC of 0x02D2 C77C … 474D 4113 from the base market, paid to the signer.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.COMPOUND_WITHDRAW_FROM_USDC,
                CUSDC_V3,
                "0x26441318",
                "withdrawFrom(address,address,address,uint256)",
                COMPOUND_WITHDRAW_FROM,
                SIGNER_WITHDRAW_FROM,
            ),
        )

        assertEquals(listOf("Withdraw all USDC from Compound V3 (cUSDCv3) of 0x02D2 C77C … 474D 4113 to you"), steps.lines)
        assertEquals(listOf("This withdraws from 0x02D2 C77C … 474D 4113, not you"), steps.warnings)
    }

    @Test
    fun randomArgumentsNeverThrowForACompoundWithdrawFrom() {
        val random = Random(20261118)
        repeat(FUZZ_ROUNDS) {
            val values = List(COMPOUND_WITHDRAW_FROM.size) { index ->
                val hex = (0 until WORD_BYTES).joinToString("") { "%02x".format(random.nextInt(0, 256)) }
                DecodedParam(COMPOUND_WITHDRAW_FROM[index], AbiValue.Word(hex))
            }

            val steps = assertNotNull(
                CallSteps.describe(
                    STEP_REGISTRY,
                    1,
                    CUSDC_V3,
                    "0x26441318",
                    "withdrawFrom(address,address,address,uint256)",
                    SIGNER,
                    BigInteger.ZERO,
                    values,
                ),
            )
            assertTrue(steps.lines.isNotEmpty() || steps.warnings.isNotEmpty())
        }
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

        assertEquals(listOf("Stake 3 ETH with Lido for stETH (referral 0x11d0 0000 … 0000 11d0)"), steps.lines)
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

    @Test
    fun anAaveGatewayDepositOfEthNamesTheWrappedSupply() {
        // Transaction 0x9bce9274, Aave V3 WETH gateway: deposit 5 ETH on behalf of the sender.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.GW_DEPOSIT_ETH,
                AAVE_GATEWAY,
                "0x474cf53d",
                "depositETH(address,address,uint16)",
                AAVE_GATEWAY_DEPOSIT,
                from = "0xf135fbe76eb10c7c74e21b30e1ce61bd1feec1c5",
                sent = BigInteger("5000000000000000000"),
            ),
        )

        assertEquals(listOf("Supply 5 ETH to Aave V3 as WETH on behalf of you"), steps.lines)
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun anAaveGatewayWithdrawOfTheWholeBalanceSaysAll() {
        // Transaction 0x9cd1c2e6, Aave V3 WETH gateway: withdraw the whole aWETH balance (2^256 - 1) to the sender.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.GW_WITHDRAW_ALL_ETH,
                AAVE_GATEWAY,
                "0x80500d20",
                "withdrawETH(address,uint256,address)",
                AAVE_GATEWAY_WITHDRAW,
                from = "0x1cdc6b515b35a6433352915a29122c2b27a88dfb",
            ),
        )

        assertEquals(listOf("Withdraw all ETH from Aave V3 to you"), steps.lines)
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun anAaveGatewayBorrowOfEthIsAtTheVariableRateAndSentToTheSigner() {
        // Transaction 0x1cc94f96, Aave V3 WETH gateway: borrow 1,300 ETH with no referral code.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.GW_BORROW_ETH,
                AAVE_GATEWAY,
                "0xe74f7b85",
                "borrowETH(address,uint256,uint16)",
                AAVE_GATEWAY_BORROW,
                from = "0xca686974913389d42f3c5f61010503daccdb487a",
            ),
        )

        assertEquals(listOf("Borrow 1,300 ETH from Aave V3 at a variable rate, sent to you"), steps.lines)
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun anAaveGatewayRepayOfEthSaysTheCapAndTheRefund() {
        // Transaction 0x3b88e523, Aave V3 WETH gateway: repay up to 7.7 ETH on behalf of the sender, sending the same 7.7 ETH.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.GW_REPAY_ETH,
                AAVE_GATEWAY,
                "0xbcc3c255",
                "repayETH(address,uint256,address)",
                AAVE_GATEWAY_REPAY,
                from = "0xdb98176d578f63d7043c761ac406a32cda81fccc",
                sent = BigInteger("7700000000000000000"),
            ),
        )

        assertEquals(
            listOf(
                "Repay up to 7.7 ETH to Aave V3 at a variable rate on behalf of you, sending 7.7 ETH (any excess is refunded)",
            ),
            steps.lines,
        )
    }

    @Test
    fun anAaveRepayWithATokensNamesTheSuppliedAsset() {
        // Transaction 0xae37b43c, Aave V3 Pool: repay 0.0052 WBTC at the variable rate with the WBTC the sender supplied.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.POOL_REPAY_WITH_ATOKENS,
                AAVE_POOL,
                "0x2dad97d4",
                "repayWithATokens(address,uint256,uint256)",
                AAVE_REPAY_WITH_ATOKENS,
                from = "0xc1af26b176ff6a984a434e8038c45f9dc4954afb",
            ),
        )

        assertEquals(listOf("Repay 0.0052 WBTC to Aave V3 at a variable rate, paid from your supplied WBTC"), steps.lines)
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun anAaveRepayWithAPermitIsApprovedBySignature() {
        // Transaction 0x37c2db38, Aave V3 Pool: repay 9,531.486401 USDC at the variable rate on behalf of the sender, with a permit.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.POOL_REPAY_WITH_PERMIT,
                AAVE_POOL,
                "0xee3e210b",
                "repayWithPermit(address,uint256,uint256,address,uint256,uint8,bytes32,bytes32)",
                AAVE_REPAY_WITH_PERMIT,
                from = "0xc468315a2df54f9c076bd5cfe5002ba211f74ca6",
            ),
        )

        assertEquals(
            listOf("Repay 9,531.486401 USDC to Aave V3 at a variable rate on behalf of you, approved by a signed permit"),
            steps.lines,
        )
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun anAaveEfficiencyModeSaysTheCategoryAndWarns() {
        // Transaction 0x417247b8, Aave V3 Pool: setUserEMode to category 1.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.POOL_SET_EMODE,
                AAVE_POOL,
                "0x28530a47",
                "setUserEMode(uint8)",
                listOf(WhitelistParam("categoryId", "uint8")),
                from = "0x7b29eefe495a94aa9a3afd2770f549e0c7167c5c",
            ),
        )

        assertEquals(listOf("Set efficiency mode on Aave V3 to category 1"), steps.lines)
        assertEquals(
            listOf("Efficiency mode changes the limits and liquidation thresholds of your Aave V3 positions"),
            steps.warnings,
        )
    }

    @Test
    fun anAaveSupplyWithAPermitIsApprovedBySignature() {
        // Transaction 0xf8d3cce7, Aave V3 Pool: supply 400,000 USDC on behalf of the sender, with a signed permit.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.POOL_SUPPLY_WITH_PERMIT,
                AAVE_POOL,
                "0x02c205f0",
                "supplyWithPermit(address,uint256,address,uint16,uint256,uint8,bytes32,bytes32)",
                listOf(
                    WhitelistParam("asset", "address"),
                    WhitelistParam("amount", "uint256"),
                    WhitelistParam("onBehalfOf", "address"),
                    WhitelistParam("referralCode", "uint16"),
                    WhitelistParam("deadline", "uint256"),
                    WhitelistParam("permitV", "uint8"),
                    WhitelistParam("permitR", "bytes32"),
                    WhitelistParam("permitS", "bytes32"),
                ),
                from = "0xba0e4165cf59aa8c9c5720fb85e48e777891c2d1",
            ),
        )

        assertEquals(listOf("Supply 400,000 USDC to Aave V3 on behalf of you, approved by a signed permit"), steps.lines)
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun aCompoundWithdrawToAnotherAccountWarns() {
        // Transaction 0xaf636d76, Compound V3 cWETHv3: withdraw 0.371294586194255254 WETH to 0x5eD4 9535 … C3DD C592, which is not the sender.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.COMPOUND_WITHDRAW_TO,
                CWETH_V3,
                "0xc3b35a7e",
                "withdrawTo(address,address,uint256)",
                listOf(
                    WhitelistParam("to", "address"),
                    WhitelistParam("asset", "address"),
                    WhitelistParam("amount", "uint256"),
                ),
                from = "0x96a56cd396d47a533930840a6165fc41a64d979a",
            ),
        )

        assertEquals(listOf("Withdraw 0.371294586194255254 WETH from Compound V3 (cWETHv3) to 0x5eD4 9535 … C3DD C592"), steps.lines)
        assertEquals(listOf("This withdraws to 0x5eD4 9535 … C3DD C592, not you"), steps.warnings)
    }

    @Test
    fun aWstEthWrapNamesTheStEthAmountByItsAddress() {
        // Transaction 0x10435947, wstETH: wrap 5 stETH, which is not a registered token, so its amount is shown in base units.
        val steps = assertNotNull(
            stepsOf(StepVectors.WSTETH_WRAP, WSTETH, "0xea598cb0", "wrap(uint256)", ONE_AMOUNT, from = "0x1bb1ea7d387c142ab874715f50741cadcfcfc407"),
        )

        assertEquals(listOf("Wrap 5000000000000000000 base units of 0xae7a b965 … 12D7 fE84 into wstETH"), steps.lines)
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun aWstEthUnwrapNamesTheWstEthAmountByItsAddress() {
        // Transaction 0x91ecabe3, wstETH: unwrap 434956435649819 base units of wstETH.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.WSTETH_UNWRAP,
                WSTETH,
                "0xde0e9a3e",
                "unwrap(uint256)",
                ONE_AMOUNT,
                from = "0xd2a7199276b18998d06d873e0adfd9be41e31395",
            ),
        )

        assertEquals(listOf("Unwrap 434956435649819 base units of 0x7f39 C581 … 935E 2Ca0 into stETH"), steps.lines)
    }

    @Test
    fun aLidoWithdrawalRequestIsOwnedByTheSigner() {
        // Transaction 0x64fba57a, Lido withdrawal queue: request one withdrawal of 8708677820110489 base units of stETH, owned by the sender.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.LIDO_REQUEST_WITHDRAWALS,
                LIDO_QUEUE,
                "0xd6681042",
                "requestWithdrawals(uint256[],address)",
                listOf(WhitelistParam("_amounts", "uint256[]"), WhitelistParam("_owner", "address")),
                from = "0x296d78ff818393cfbc1623c6a77926e28f02b3b3",
            ),
        )

        assertEquals(
            listOf("Request a withdrawal of 8708677820110489 base units of 0xae7a b965 … 12D7 fE84 as ETH for you"),
            steps.lines,
        )
        assertEquals(emptyList(), steps.warnings)
    }

    @Test
    fun aLidoWithdrawalClaimPaysTheSigner() {
        // Transaction 0x204a1d84, Lido withdrawal queue: claim the ETH of withdrawal request 141654.
        val steps = assertNotNull(
            stepsOf(
                StepVectors.LIDO_CLAIM_WITHDRAWALS,
                LIDO_QUEUE,
                "0xe3afe0a3",
                "claimWithdrawals(uint256[],uint256[])",
                listOf(WhitelistParam("_requestIds", "uint256[]"), WhitelistParam("_hints", "uint256[]")),
                from = "0xe58688afcc92132337158eea09ecae37dfc6db06",
            ),
        )

        assertEquals(listOf("Claim the ETH of Lido withdrawal request #141654 to you"), steps.lines)
    }

    private companion object {
        const val WORD_BYTES = 32
    }
}
