package app.lensly.core.evm

import app.lensly.core.parse.DecodeBudget
import app.lensly.core.parse.ParserLimits
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class RlpTest {

    @Test
    fun decodesCanonicalStringsAndLists() {
        assertContentEquals(byteArrayOf(0x00), (Rlp.decode(EvmFixtures.hex("00")) as Rlp.Str).bytes)
        assertContentEquals(byteArrayOf(0x7F), (Rlp.decode(EvmFixtures.hex("7f")) as Rlp.Str).bytes)
        assertContentEquals(byteArrayOf(0x80.toByte()), (Rlp.decode(EvmFixtures.hex("8180")) as Rlp.Str).bytes)
        assertContentEquals(ByteArray(0), (Rlp.decode(EvmFixtures.hex("80")) as Rlp.Str).bytes)
        assertEquals(0, (Rlp.decode(EvmFixtures.hex("c0")) as Rlp.Lst).items.size)
        assertEquals(3, (Rlp.decode(EvmFixtures.hex("c3010203")) as Rlp.Lst).items.size)
        // A 56-byte string is the shortest long form.
        assertEquals(56, (Rlp.decode(EvmFixtures.hex("b838" + "00".repeat(56))) as Rlp.Str).bytes.size)
    }

    @Test
    fun rejectsNonCanonicalEncodings() {
        val cases = mapOf(
            "single byte wrapped as a string" to "8100",
            "long string form for 5 bytes" to "b80105",
            "length with a leading zero" to "b9003801",
            "long list form for length 0" to "f800",
            "long list form for length 10" to "f80a",
        )
        for ((name, hex) in cases) {
            assertFailsWith<MalformedTxException>(name) { Rlp.decode(EvmFixtures.hex(hex)) }
        }
    }

    @Test
    fun rejectsTruncatedTrailingAndEmptyInput() {
        assertFailsWith<MalformedTxException> { Rlp.decode(EvmFixtures.hex("82")) }
        assertFailsWith<MalformedTxException> { Rlp.decode(EvmFixtures.hex("c301")) }
        // The inner string claims two bytes, but the enclosing list ends after one.
        assertFailsWith<MalformedTxException> { Rlp.decode(EvmFixtures.hex("c28201")) }
        assertFailsWith<MalformedTxException> { Rlp.decode(EvmFixtures.hex("0000")) }
        assertFailsWith<MalformedTxException> { Rlp.decode(ByteArray(0)) }
    }

    @Test
    fun rejectsNestingDeeperThanEight() {
        // levels nested lists around an empty string: c_levels c_(levels-1) ... c1 80.
        fun nested(levels: Int): ByteArray =
            EvmFixtures.hex((levels downTo 1).joinToString("") { "%02x".format(0xC0 + it) } + "80")

        Rlp.decode(nested(8))
        val error = assertFailsWith<MalformedTxException> { Rlp.decode(nested(9)) }
        assertTrue(error.message!!.contains("nested"), error.message)
    }

    @Test
    fun refusesInputLargerThanTheSizeLimit() {
        // The size is checked before anything is parsed, so the content does not matter.
        val oversized = ByteArray(ParserLimits.MAX_SIGN_DATA_BYTES + 1)
        val error = assertFailsWith<MalformedTxException> { Rlp.decode(oversized) }
        assertEquals("RLP is larger than ${ParserLimits.MAX_SIGN_DATA_BYTES} bytes", error.message)
    }

    @Test
    fun refusesMoreItemsThanTheCountLimit() {
        // A list header plus MAX_RLP_ITEMS single-byte items is exactly the limit; one more item is refused.
        val atLimit = EvmFixtures.rlpList(List(ParserLimits.MAX_RLP_ITEMS - 1) { EvmFixtures.uint(1) })
        Rlp.decode(atLimit)
        val overLimit = EvmFixtures.rlpList(List(ParserLimits.MAX_RLP_ITEMS) { EvmFixtures.uint(1) })
        val error = assertFailsWith<MalformedTxException> { Rlp.decode(overLimit) }
        assertEquals("RLP has more than ${ParserLimits.MAX_RLP_ITEMS} items", error.message)
    }

    @Test
    fun stopsWhenTheDecodeBudgetIsSpent() {
        var now = 0L
        val budget = DecodeBudget("RLP", ParserLimits.MAX_RLP_ITEMS, maxNanos = 100) { now += 10; now }
        val payload = EvmFixtures.rlpList(List(20) { EvmFixtures.uint(1) })
        val error = assertFailsWith<MalformedTxException> { Rlp.decode(payload, budget) }
        assertTrue(error.message!!.contains("time budget"), error.message)
    }
}
