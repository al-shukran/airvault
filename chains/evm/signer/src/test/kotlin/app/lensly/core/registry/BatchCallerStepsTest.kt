package app.lensly.core.registry

import app.airvault.abi.AbiValue
import app.airvault.abi.DecodedParam
import app.airvault.abi.WhitelistParam
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

/** Multicall3, which is the message sender of a call a Multicall3 batch makes, and its short form as a step shows it. */
private const val MULTICALL3 = "0xca11bde05977b3631167028862be2a173976ca11"
private const val MULTICALL3_SHORT = "0xcA11 bde0 … 3976 CA11"
private const val OTHER = "0x1593d3980018812a3f95290cf896437f3ea25343"
private const val OTHER_SHORT = "0x1593 D398 … 3eA2 5343"
private const val AAVE_POOL = "0x87870bca3f3fd6335c3f4ce8392d69350b4fa4e2"
private const val AAVE_GATEWAY = "0xd01607c3c5ecaba394d8be377a08590149325722"
private const val CUSDC_V3 = "0xc3d688b66703497daa19211eedff47f25384cdc3"
private const val BULKER = "0xa397a8c2086c554b531c02e29f3291c9704b00c7"
private const val COW_SETTLEMENT = "0x9008d19f58aabd9ed0d60971565aa8510560ab41"
private const val CWETH_V3 = "0xa17581a9e3356d9a858b789d68b4d866e593ae94"
private const val ONE_USDC = 1_000_000L
private const val WORD = 32

private val YOU = Regex("\\byou(r)?\\b")

/** A decoded argument of [type] named [name], holding [word]. */
private fun word(name: String, type: String, word: ByteArray) = DecodedParam(WhitelistParam(name, type), AbiValue.Word(word.toHex()))

private fun address(name: String, value: String) = word(name, "address", abiAddressWord(value))

private fun number(name: String, value: Long, type: String = "uint256") = word(name, type, abiWord(BigInteger.valueOf(value)))

/** The steps of the call to [contract] inside a Multicall3 batch signed by [SIGNER], or straight from it when [caller] is the signer. */
private fun stepsOf(
    contract: String,
    selector: String,
    signature: String,
    params: List<DecodedParam>,
    caller: String = MULTICALL3,
    sent: BigInteger = BigInteger.ZERO,
): CallSteps = assertNotNull(CallSteps.describe(STEP_REGISTRY, 1, contract, selector, signature, SIGNER, sent, params, caller))

private fun aaveWithdraw(to: String, caller: String = MULTICALL3) = stepsOf(
    AAVE_POOL,
    "0x69328dec",
    "withdraw(address,uint256,address)",
    listOf(address("asset", USDC), number("amount", ONE_USDC), address("to", to)),
    caller,
)

/** Asserts that no step line calls anyone "you". The warnings say "not you" by design. */
private fun assertNamesNoYou(steps: CallSteps) {
    steps.lines.forEach { assertTrue(!YOU.containsMatchIn(it), it) }
}

class BatchCallerStepsTest {

    @Test
    fun anAaveStepInABatchNamesMulticall3AndWarnsOfEveryRecipientButTheSigner() {
        val toBatch = aaveWithdraw(MULTICALL3)
        assertEquals(listOf("Withdraw 1 USDC from Aave V3 to Multicall3"), toBatch.lines)
        assertEquals(listOf("This withdraws to $MULTICALL3_SHORT, not you"), toBatch.warnings)
        assertEquals(listOf(MULTICALL3), toBatch.addresses)
        assertNamesNoYou(toBatch)
        assertAddressesInGroupsOfFour(toBatch)

        val toOther = aaveWithdraw(OTHER)
        assertEquals(listOf("Withdraw 1 USDC from Aave V3 to $OTHER_SHORT"), toOther.lines)
        assertEquals(listOf("This withdraws to $OTHER_SHORT, not you"), toOther.warnings)
        assertEquals(listOf(OTHER), toOther.addresses)

        // The signer is paid: nothing is warned, and no recipient row is added.
        val toSigner = aaveWithdraw(SIGNER)
        assertEquals(listOf("Withdraw 1 USDC from Aave V3 to you"), toSigner.lines)
        assertEquals(emptyList(), toSigner.warnings)
        assertEquals(emptyList(), toSigner.addresses)
    }

    @Test
    fun anAaveStepOutsideABatchIsUnchanged() {
        val toOther = aaveWithdraw(OTHER, caller = SIGNER)
        assertEquals(listOf("This withdraws to $OTHER_SHORT, not you"), toOther.warnings)
        assertEquals(emptyList(), toOther.addresses)
        assertEquals(listOf("Withdraw 1 USDC from Aave V3 to you"), aaveWithdraw(SIGNER, caller = SIGNER).lines)
    }

    @Test
    fun anAaveGatewayBorrowAndATokenRepayInABatchNameMulticall3AsTheCaller() {
        val borrow = stepsOf(
            AAVE_GATEWAY,
            "0xe74f7b85",
            "borrowETH(address,uint256,uint16)",
            listOf(address("unused", AAVE_POOL), number("amount", ONE_USDC), number("referralCode", 0, "uint16")),
        )
        assertTrue(borrow.lines.single().endsWith("at a variable rate, sent to Multicall3"), borrow.lines.single())
        assertEquals(listOf("This sends the borrowed ETH to $MULTICALL3_SHORT, not you"), borrow.warnings)
        assertEquals(listOf(MULTICALL3), borrow.addresses)

        val repay = stepsOf(
            AAVE_POOL,
            "0x2dad97d4",
            "repayWithATokens(address,uint256,uint256)",
            listOf(address("asset", USDC), number("amount", ONE_USDC), number("interestRateMode", 2)),
        )
        assertEquals(listOf("Repay 1 USDC to Aave V3 at a variable rate, paid from Multicall3's supplied USDC"), repay.lines)
        assertNamesNoYou(repay)
    }

    @Test
    fun aCompoundStepInABatchNamesMulticall3AndWarnsOfEveryAccountButTheSigner() {
        val supplyTo = stepsOf(
            CUSDC_V3,
            "0x4232cd63",
            "supplyTo(address,address,uint256)",
            listOf(address("dst", MULTICALL3), address("asset", USDC), number("amount", ONE_USDC)),
        )
        assertEquals(listOf("Supply 1 USDC to Compound V3 (cUSDCv3) for Multicall3"), supplyTo.lines)
        assertEquals(listOf("This supplies to $MULTICALL3_SHORT, not you"), supplyTo.warnings)
        assertEquals(listOf(MULTICALL3), supplyTo.addresses)

        val withdrawFrom = stepsOf(
            CUSDC_V3,
            "0x26441318",
            "withdrawFrom(address,address,address,uint256)",
            listOf(address("src", MULTICALL3), address("to", OTHER), address("asset", USDC), number("amount", ONE_USDC)),
        )
        assertEquals(listOf("Withdraw 1 USDC from Compound V3 (cUSDCv3) of Multicall3 to $OTHER_SHORT"), withdrawFrom.lines)
        assertEquals(listOf("This withdraws from $MULTICALL3_SHORT, not you", "This withdraws to $OTHER_SHORT, not you"), withdrawFrom.warnings)
        assertEquals(listOf(MULTICALL3, OTHER), withdrawFrom.addresses)

        // Straight from the signer the same call adds no recipient rows.
        val direct = stepsOf(
            CUSDC_V3,
            "0x4232cd63",
            "supplyTo(address,address,uint256)",
            listOf(address("dst", OTHER), address("asset", USDC), number("amount", ONE_USDC)),
            caller = SIGNER,
        )
        assertEquals(listOf("This supplies to $OTHER_SHORT, not you"), direct.warnings)
        assertEquals(emptyList(), direct.addresses)
    }

    /** The bulker's `invoke` of one [action] whose data is [data], each a 32-byte word. */
    private fun bulker(action: String, vararg data: ByteArray, caller: String = MULTICALL3) = stepsOf(
        BULKER,
        "0x555029a6",
        "invoke(bytes32[],bytes[])",
        listOf(
            DecodedParam(WhitelistParam("actions", "bytes32[]"), AbiValue.Items(listOf(AbiValue.Word(action.toByteArray(Charsets.US_ASCII).copyOf(WORD).toHex())))),
            DecodedParam(WhitelistParam("data", "bytes[]"), AbiValue.Items(listOf(AbiValue.Dynamic(data.joinToString("") { it.toHex() })))),
        ),
        caller,
    )

    @Test
    fun aBulkerStepInABatchNamesMulticall3AndWarnsOfEveryRecipientButTheSigner() {
        val market = abiAddressWord(CWETH_V3)
        val toBatch = bulker("ACTION_SUPPLY_NATIVE_TOKEN", market, abiAddressWord(MULTICALL3), abiWord(BigInteger.TEN.pow(18)))
        assertEquals(listOf("Supply 1 ETH to Compound V3 (cWETHv3) for Multicall3"), toBatch.lines)
        assertEquals(listOf("This supplies to $MULTICALL3_SHORT, not you"), toBatch.warnings)
        assertEquals(listOf(MULTICALL3), toBatch.addresses)

        val toOther = bulker("ACTION_WITHDRAW_NATIVE_TOKEN", market, abiAddressWord(OTHER), abiWord(BigInteger.TEN.pow(18)))
        assertEquals(listOf("Withdraw 1 ETH from Compound V3 (cWETHv3) to $OTHER_SHORT"), toOther.lines)
        assertEquals(listOf("This withdraws to $OTHER_SHORT, not you"), toOther.warnings)
        assertEquals(listOf(OTHER), toOther.addresses)

        val all = bulker("ACTION_SUPPLY_NATIVE_TOKEN", market, abiAddressWord(SIGNER), ByteArray(WORD) { -1 })
        assertTrue(all.lines.single().startsWith("Supply Multicall3's borrow balance on Compound V3 (cWETHv3) for you,"), all.lines.single())
        assertEquals(emptyList(), all.warnings)
        assertEquals(emptyList(), all.addresses)

        val direct = bulker("ACTION_SUPPLY_NATIVE_TOKEN", market, abiAddressWord(OTHER), abiWord(BigInteger.TEN), caller = SIGNER)
        assertEquals(listOf("This supplies to $OTHER_SHORT, not you"), direct.warnings)
        assertEquals(emptyList(), direct.addresses)
        assertTrue(YOU.containsMatchIn(bulker("ACTION_SUPPLY_NATIVE_TOKEN", market, abiAddressWord(SIGNER), ByteArray(WORD) { -1 }, caller = SIGNER).lines.single()))
    }

    /** The decoded arguments of a pre-signature of an order owned by [owner]. */
    private fun preSign(owner: String): List<DecodedParam> {
        val uid = "9153" + "00".repeat(WORD - 2) + owner.removePrefix("0x") + "69193fd0"
        return listOf(
            DecodedParam(WhitelistParam("orderUid", "bytes"), AbiValue.Dynamic(uid)),
            DecodedParam(WhitelistParam("signed", "bool"), AbiValue.Word("00".repeat(WORD - 1) + "01")),
        )
    }

    private fun cow(owner: String, caller: String = MULTICALL3) =
        stepsOf(COW_SETTLEMENT, "0xec6cb13f", "setPreSignature(bytes,bool)", preSign(owner), caller)

    @Test
    fun aCowPreSignatureInABatchMustBeOwnedByMulticall3ElseItReverts() {
        val own = cow(MULTICALL3)
        assertEquals(1, own.lines.size)
        assertTrue(own.lines.single().contains(" for Multicall3, valid until "), own.lines.single())
        assertNamesNoYou(own)

        // The settlement takes the pre-signature from its message sender, so the signer's order reverts inside a batch.
        val signers = cow(SIGNER)
        assertEquals(emptyList(), signers.lines)
        assertEquals(listOf("This pre-signs an order owned by you, not Multicall3, so the call reverts"), signers.warnings)

        val others = cow(OTHER)
        assertEquals(listOf("This pre-signs an order owned by $OTHER_SHORT, not Multicall3, so the call reverts"), others.warnings)
        assertAddressesInGroupsOfFour(others)

        // Straight from the signer nothing changes.
        assertTrue(cow(SIGNER, caller = SIGNER).lines.single().contains(" for you, valid until "))
        assertEquals(listOf("This pre-signs an order owned by $OTHER_SHORT, not you, so the call reverts"), cow(OTHER, caller = SIGNER).warnings)
    }
}
