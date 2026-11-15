package app.lumio.tokens

import app.lensly.core.wallet.toHex
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.Hash

/**
 * The ABI encoding of the token reads, checked against the selectors that Keccak-256 gives and against hand-written call data and replies.
 * A word is 64 hex digits: a zero-filled word is written with [word], so a test shows each field of the layout.
 */
class AbiTest {
    private val owner = "0x" + "ab".repeat(20)

    private fun word(n: Long): String = "%064x".format(n)

    private fun selector(signature: String): String = "0x" + Hash.sha3(signature.toByteArray()).copyOfRange(0, 4).toHex()

    @Test
    fun theSelectorsAreTheKeccakOfTheirSignatures() {
        assertEquals(selector("balanceOf(address)"), Erc20.BALANCE_OF)
        assertEquals(selector("symbol()"), Erc20.SYMBOL)
        assertEquals(selector("name()"), Erc20.NAME)
        assertEquals(selector("decimals()"), Erc20.DECIMALS)
        assertEquals(selector("aggregate3((address,bool,bytes)[])"), Multicall3.AGGREGATE3)
    }

    @Test
    fun balanceOfIsTheSelectorAndTheOwnerAsOneWord() {
        assertEquals("0x70a08231" + "0".repeat(24) + "ab".repeat(20), Erc20.balanceOf(owner))
    }

    @Test
    fun aWordHoldsAnUnsignedIntegerAndRefusesOthers() {
        assertEquals(BigInteger.valueOf(6), Abi.uint(Abi.word(6)))
        assertEquals(BigInteger.ONE.shiftLeft(255), Abi.uint(Abi.bytes("0x8" + "0".repeat(63))))
        assertFailsWith<IllegalArgumentException> { Abi.word(BigInteger.valueOf(-1)) }
        assertFailsWith<IllegalArgumentException> { Abi.word(BigInteger.ONE.shiftLeft(256)) }
    }

    @Test
    fun aReplyThatIsNotHexOrIsTooShortDoesNotDecode() {
        assertFailsWith<AbiException> { Abi.bytes("0xabc") }
        assertFailsWith<AbiException> { Abi.bytes("12") }
        assertFailsWith<AbiException> { Abi.uint(Abi.bytes("0x" + "00".repeat(31))) }
    }

    @Test
    fun aDynamicStringIsReadWithItsLength() {
        val reply = Abi.bytes("0x" + word(32) + word(4) + "55534443".padEnd(64, '0'))
        assertEquals("USDC", Erc20.text(reply))
    }

    @Test
    fun aBytes32SymbolIsReadLeftAligned() {
        val mkr = "0x4d4b520000000000000000000000000000000000000000000000000000000000"
        assertEquals("MKR", Erc20.text(Abi.bytes(mkr)))
    }

    @Test
    fun anEmptyOrBrokenStringIsNotText() {
        assertNull(Erc20.text(Abi.bytes("0x" + word(32) + word(0))))
        assertNull(Erc20.text(Abi.bytes("0x" + word(32) + word(500) + "00".repeat(32))))
        assertNull(Erc20.text(Abi.bytes("0x" + word(0) + "00".repeat(40))))
    }

    @Test
    fun aMulticallBatchIsEncodedAsTheAbiSpecifiesIt() {
        // One call to 0x...01 with allowFailure set and four bytes of data. The argument is an offset to the array, the array's length,
        // an offset to its one tuple, and the tuple: address, allowFailure, an offset of three words to the bytes, the length, then the
        // data padded to a word.
        val call = Multicall3.Call(target = "0x" + "0".repeat(39) + "1", allowFailure = true, data = "0x12345678")
        val expected = Multicall3.AGGREGATE3 + word(32) + word(1) + word(32) +
            word(1) + word(1) + word(96) + word(4) + "12345678".padEnd(64, '0')
        assertEquals(expected, Multicall3.aggregate3(listOf(call)))
    }

    @Test
    fun aMulticallBatchOfTwoCallsPutsEachTupleAfterItsOffset() {
        val first = Multicall3.Call("0x" + "0".repeat(39) + "1", true, "0x" + "00".repeat(4))
        val second = Multicall3.Call("0x" + "0".repeat(39) + "2", false, "0x" + "00".repeat(36))
        val encoded = Multicall3.aggregate3(listOf(first, second))
        val firstTupleSize = 5 * 32
        val secondOffset = 2 * 32 + firstTupleSize
        assertEquals(secondOffset.toLong(), BigInteger(encoded.substring(10 + 3 * 64, 10 + 4 * 64), 16).toLong())
    }

    @Test
    fun aMulticallReplyDecodesEachAnswerInOrder() {
        val reply = "0x" + word(32) + word(2) + word(64) + word(64 + 128) +
            // Answer one: success, return data of one word holding 42.
            word(1) + word(64) + word(32) + word(42) +
            // Answer two: a failure with no return data.
            word(0) + word(64) + word(0)
        val results = Multicall3.decodeAggregate3(reply, expected = 2)

        assertTrue(results[0].success)
        assertEquals(BigInteger.valueOf(42), Abi.uint(results[0].data))
        assertEquals(false, results[1].success)
        assertEquals(0, results[1].data.size)
    }

    @Test
    fun aMulticallReplyWithTheWrongCountOrAPointerPastItsEndIsRefused() {
        val reply = "0x" + word(32) + word(1) + word(32) + word(1) + word(64) + word(0)
        assertFailsWith<AbiException> { Multicall3.decodeAggregate3(reply, expected = 2) }
        val pointer = "0x" + word(32) + word(1) + word(32) + word(1) + word(64) + word(4096)
        assertFailsWith<AbiException> { Multicall3.decodeAggregate3(pointer, expected = 1) }
    }
}
