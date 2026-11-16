package app.lumio.evm

import app.lensly.app.export.partsOf
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.EthSignRequestEncoder
import app.lensly.core.ur.UrScanner
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.junit.Test
import org.web3j.crypto.TransactionDecoder
import org.web3j.utils.Numeric

/**
 * The advanced sheet's rules: each field is checked on its own, the warnings say when an edit deserves a second look, and the edited
 * values end up in the transaction that the sign request carries.
 */
class EvmAdvancedTest {
    private val sepolia = EvmChain(11155111L, "Sepolia", "ETH", 18, testnet = true, explorerUrl = null)

    /** The send the network prepared: nonce 7, 21000 gas, a fee cap of 30 gwei and a priority fee of 1.5 gwei, with 1 ETH in the balance. */
    private val suggested = PreparedSend(
        chain = sepolia,
        index = 0,
        from = FROM,
        to = RECIPIENT,
        valueWei = BigInteger.TEN.pow(16),
        nonce = 7,
        gasLimit = BigInteger.valueOf(21_000),
        maxFeePerGas = gwei("30"),
        maxPriorityFeePerGas = gwei("1.5"),
        balanceWei = BigInteger.TEN.pow(18),
    )

    /** The fields of [suggested] with one of them replaced. */
    private fun edit(
        nonce: String = "7",
        gasLimit: String = "21000",
        maxFee: String = "30",
        priority: String = "1.5",
    ) = EvmAdvancedFields(nonce, gasLimit, maxFee, priority)

    private fun validOf(fields: EvmAdvancedFields): EvmAdvancedCheck.Valid =
        assertIs<EvmAdvancedCheck.Valid>(EvmAdvanced.check(suggested, fields))

    private fun errorsOf(fields: EvmAdvancedFields): Map<EvmField, String> =
        assertIs<EvmAdvancedCheck.Invalid>(EvmAdvanced.check(suggested, fields)).errors

    @Test
    fun theSheetIsPrefilledFromTheSendAndTheSuggestedSendIsValidWithNoWarnings() {
        assertEquals(edit(), EvmAdvanced.fieldsOf(suggested))
        val check = validOf(EvmAdvanced.fieldsOf(suggested))
        assertEquals(emptyList(), check.warnings)
        assertContentEquals(suggested.payload, check.send.payload)
    }

    @Test
    fun theNonceIsAWholeNumberZeroOrMore() {
        for (bad in listOf("-1", "1.5", "", "abc", "99999999999999999999")) {
            assertEquals("Enter the nonce as a whole number, 0 or more.", errorsOf(edit(nonce = bad))[EvmField.NONCE], "nonce '$bad'")
        }
        assertEquals(0L, validOf(edit(nonce = "0")).send.nonce)
    }

    @Test
    fun theGasLimitIsAWholeNumberOfAtLeastATransfer() {
        assertEquals("A transfer needs at least 21000 gas.", errorsOf(edit(gasLimit = "20999"))[EvmField.GAS_LIMIT])
        assertEquals("Enter the gas limit as a whole number.", errorsOf(edit(gasLimit = "21.5"))[EvmField.GAS_LIMIT])
        assertEquals(BigInteger.valueOf(500_000), validOf(edit(gasLimit = "500000")).send.gasLimit)
    }

    @Test
    fun theFeesAreGweiWithAtMostNineDecimalPlaces() {
        assertEquals("Enter the fee in gwei, such as 30.", errorsOf(edit(maxFee = "abc"))[EvmField.MAX_FEE])
        assertEquals("Use at most 9 decimal places for gwei.", errorsOf(edit(maxFee = "30.0000000001"))[EvmField.MAX_FEE])
        assertEquals(gwei("30.000000001"), validOf(edit(maxFee = "30.000000001")).send.maxFeePerGas)
    }

    @Test
    fun theFeeCapMustBeMoreThanZeroAndThePriorityFeeMayBeZero() {
        assertEquals("The fee cap must be more than zero.", errorsOf(edit(maxFee = "0"))[EvmField.MAX_FEE])
        assertEquals(BigInteger.ZERO, validOf(edit(priority = "0")).send.maxPriorityFeePerGas)
    }

    @Test
    fun thePriorityFeeCannotBeMoreThanTheFeeCap() {
        assertEquals(
            "The priority fee cannot be more than the fee cap.",
            errorsOf(edit(maxFee = "30", priority = "31"))[EvmField.MAX_PRIORITY],
        )
        assertEquals(gwei("30"), validOf(edit(maxFee = "30", priority = "30")).send.maxPriorityFeePerGas)
    }

    @Test
    fun aSendThatCostsMoreThanTheBalanceIsRefusedOnTheFeeCap() {
        // 0.01 ETH of value and 21000 gas at 30 gwei is 0.01063 ETH, under the 0.011 balance. At 100 gwei it is 0.0121 ETH, over it.
        val poor = PreparedSend(
            chain = sepolia,
            index = 0,
            from = FROM,
            to = RECIPIENT,
            valueWei = BigInteger.TEN.pow(16),
            nonce = 7,
            gasLimit = BigInteger.valueOf(21_000),
            maxFeePerGas = gwei("30"),
            maxPriorityFeePerGas = gwei("1.5"),
            balanceWei = BigInteger.valueOf(11) * BigInteger.TEN.pow(15),
        )
        assertIs<EvmAdvancedCheck.Valid>(EvmAdvanced.check(poor, edit(maxFee = "30")))
        assertEquals(
            "The balance is 0.011 ETH, but this send can cost up to 0.0121 ETH at these fees.",
            assertIs<EvmAdvancedCheck.Invalid>(EvmAdvanced.check(poor, edit(maxFee = "100"))).errors[EvmField.MAX_FEE],
        )
    }

    @Test
    fun aNonceBelowThePendingOneIsWarnedAsAReplacement() {
        assertEquals(
            listOf("The nonce is below the pending nonce 7. This transaction replaces a pending one."),
            validOf(edit(nonce = "6")).warnings,
        )
    }

    @Test
    fun aFeeMoreThanThreeTimesTheSuggestedOneIsWarned() {
        assertEquals(listOf("The fee is more than 3 times the suggested fee."), validOf(edit(maxFee = "91")).warnings)
        assertEquals(emptyList(), validOf(edit(maxFee = "90")).warnings)
        assertEquals(listOf("The fee is more than 3 times the suggested fee."), validOf(edit(priority = "4.6")).warnings)
    }

    @Test
    fun theCheckedSendCarriesTheEditedValuesAndTheSuggestedAmountAndParties() {
        val send = validOf(edit(nonce = "9", gasLimit = "30000", maxFee = "40", priority = "2")).send
        assertEquals(9L, send.nonce)
        assertEquals(BigInteger.valueOf(30_000), send.gasLimit)
        assertEquals(gwei("40"), send.maxFeePerGas)
        assertEquals(gwei("2"), send.maxPriorityFeePerGas)
        assertEquals(suggested.valueWei, send.valueWei)
        assertEquals(suggested.to, send.to)
    }

    @Test
    fun theSignRequestCarriesTheEditedTransaction() {
        val send = validOf(edit(nonce = "9", gasLimit = "30000", maxFee = "40", priority = "2")).send
        val requestId = ByteArray(16) { 0x33 }

        val parsed = readRequest(partsOf(EthSignRequestEncoder.encode(EvmSend.request(send, FINGERPRINT, requestId))))

        assertContentEquals(send.payload, parsed.signData)
        assertFalse(send.payload.contentEquals(suggested.payload))
        // The payload is the transaction with the edited values: the nonce and the gas limit are read back, and the fees match a send built with them.
        val decoded = TransactionDecoder.decode(Numeric.toHexString(parsed.signData))
        assertEquals(BigInteger.valueOf(9), decoded.nonce)
        assertEquals(BigInteger.valueOf(30_000), decoded.gasLimit)
        val built = PreparedSend(sepolia, 0, FROM, RECIPIENT, suggested.valueWei, 9, BigInteger.valueOf(30_000), gwei("40"), gwei("2"), suggested.balanceWei)
        assertContentEquals(built.payload, parsed.signData)
    }

    /** The request that a camera reads from [parts]. */
    private fun readRequest(parts: List<String>): EthSignRequest {
        val scanner = UrScanner()
        parts.forEach { scanner.receive(it) }
        return assertIs<EthSignRequest.Result.Ok>(EthSignRequest.decode(assertNotNull(scanner.result()))).request
    }

    private companion object {
        const val FROM = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
        const val RECIPIENT = "0x52908400098527886E0F7030069857D2E4169EE7"
        const val FINGERPRINT = 0x12345678L

        /** [gwei] as wei. */
        fun gwei(text: String): BigInteger = BigDecimal(text).movePointRight(9).toBigIntegerExact()
    }
}
