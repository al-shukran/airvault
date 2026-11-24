package app.lumio.tokens

import app.lumio.evm.RpcFailure
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** A token the owner types in: the address is checked before any call, and the contract must answer as an ERC-20 token does. */
class CustomTokenLookupTest {
    private val token = "0x" + "11".repeat(20)

    private fun lookup(reads: ScriptedReads, raw: String = token): Token = runBlocking { CustomTokenLookup.lookup(reads, ETHEREUM, raw) }

    private fun revert(): Nothing = throw RpcFailure(RpcFailure.Reason.ERROR, "The endpoint returned a JSON-RPC error.")

    /** A contract that answers all three reads: decimals, symbol and name. */
    private fun erc20(decimals: Long = 6, symbol: String = "USDC", name: String = "USD Coin"): ScriptedReads = ScriptedReads { _, data ->
        when (data) {
            Erc20.DECIMALS -> "0x" + Reply.uint(decimals)
            Erc20.SYMBOL -> "0x" + Reply.text(symbol)
            Erc20.NAME -> "0x" + Reply.text(name)
            else -> error("unexpected call $data")
        }
    }

    @Test
    fun aTokenIsReadFromTheChainWithItsSymbolNameAndDecimals() {
        assertEquals(Token(1L, token, "USDC", "USD Coin", 6), lookup(erc20()))
    }

    @Test
    fun anUppercaseAddressIsReadAsItsLowercaseContract() {
        val reads = erc20()
        val upper = "0x" + "AA".repeat(20)

        assertEquals("0x" + "aa".repeat(20), lookup(reads, raw = "  $upper ").contract)
        assertTrue(reads.calls.all { it.first == "0x" + "aa".repeat(20) })
    }

    @Test
    fun aMixedCaseAddressWithAWrongChecksumIsRefusedBeforeAnyCall() {
        val reads = erc20()

        assertEquals(
            "This address has a typo: its capital letters don't match its checksum.",
            assertFailsWith<IllegalArgumentException> { lookup(reads, "0x5aAeb6053f3E94C9b9A09f33669435E7Ef1BeAed") }.message,
        )
        assertEquals(0, reads.calls.size)
    }

    @Test
    fun aMixedCaseAddressWithAValidChecksumIsAccepted() {
        assertEquals("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed", lookup(erc20(), "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed").contract)
    }

    @Test
    fun aLowercaseAddressIsAccepted() {
        assertEquals("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed", lookup(erc20(), "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed").contract)
    }

    @Test
    fun aNameThatRevertsIsTheSymbol() {
        val reads = ScriptedReads { _, data ->
            when (data) {
                Erc20.DECIMALS -> "0x" + Reply.uint(18)
                Erc20.SYMBOL -> "0x" + Reply.text("WETH")
                else -> revert()
            }
        }

        assertEquals("WETH", lookup(reads).name)
    }

    @Test
    fun aSymbolInTheOldBytes32FormIsRead() {
        val reads = ScriptedReads { _, data ->
            when (data) {
                Erc20.DECIMALS -> "0x" + Reply.uint(18)
                Erc20.SYMBOL -> "0x" + "4d4b52".padEnd(64, '0')
                else -> revert()
            }
        }

        assertEquals("MKR", lookup(reads).symbol)
    }

    @Test
    fun anAddressWithNoContractIsRefusedWithThatWords() {
        val reads = ScriptedReads { _, _ -> "0x" }

        assertEquals("No contract is at this address on Ethereum.", assertFailsWith<IllegalArgumentException> { lookup(reads) }.message)
    }

    @Test
    fun aContractThatRevertsOnDecimalsIsNotAnERC20Token() {
        val reads = ScriptedReads { _, _ -> revert() }

        assertTrue(assertFailsWith<IllegalArgumentException> { lookup(reads) }.message!!.contains("did not answer decimals()"))
    }

    @Test
    fun aContractWithoutSymbolIsRefused() {
        val reads = ScriptedReads { _, data ->
            if (data == Erc20.DECIMALS) "0x" + Reply.uint(6) else revert()
        }

        assertTrue(assertFailsWith<IllegalArgumentException> { lookup(reads) }.message!!.contains("gives no symbol()"))
    }

    @Test
    fun decimalsAboveThirtySixAndUnusableSymbolsAreRefused() {
        val tooManyDecimals = assertFailsWith<IllegalArgumentException> { lookup(erc20(decimals = 40)) }
        assertTrue(tooManyDecimals.message!!.contains("up to 36"))
        val control = assertFailsWith<IllegalArgumentException> { lookup(erc20(symbol = "US\u0007DC")) }
        assertEquals("The token's symbol is not one Lumio can show.", control.message)
        val long = assertFailsWith<IllegalArgumentException> { lookup(erc20(symbol = "S".repeat(25))) }
        assertEquals("The token's symbol is not one Lumio can show.", long.message)
    }

    @Test
    fun anAddressThatIsNotAContractIsRefusedBeforeAnyCall() {
        val reads = erc20()

        assertEquals("Enter a contract address: 0x and 40 hex digits.", assertFailsWith<IllegalArgumentException> { lookup(reads, "USDC") }.message)
        assertEquals("Enter a contract address: 0x and 40 hex digits.", assertFailsWith<IllegalArgumentException> { lookup(reads, "0x1234") }.message)
        assertEquals("That is the zero address, not a token contract.", assertFailsWith<IllegalArgumentException> { lookup(reads, "0x" + "0".repeat(40)) }.message)
        assertEquals(0, reads.calls.size)
    }

    @Test
    fun anEndpointFailureIsNotTheContractsFault() {
        val reads = ScriptedReads { _, _ -> throw RpcFailure(RpcFailure.Reason.UNREACHABLE, "The endpoint did not answer.") }

        assertEquals(RpcFailure.Reason.UNREACHABLE, assertFailsWith<RpcFailure> { lookup(reads) }.reason)
    }

    @Test
    fun aDecimalsWordAboveTheRangeIsRefusedNotTruncated() {
        val huge = BigInteger.ONE.shiftLeft(255)
        val reads = ScriptedReads { _, data -> if (data == Erc20.DECIMALS) "0x8" + "0".repeat(63) else revert() }

        assertEquals("The token reports $huge decimals. Lumio accepts up to 36.", assertFailsWith<IllegalArgumentException> { lookup(reads) }.message)
    }
}
