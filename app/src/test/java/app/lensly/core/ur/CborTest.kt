package app.lensly.core.ur

import app.lensly.core.parse.DecodeBudget
import app.lensly.core.parse.ParserLimits
import app.lensly.core.wallet.toHex
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric

class CborTest {

    @Test
    fun integersUseShortestForm() {
        assertEquals("182c", Cbor.encode(Cbor.Uint(44)).toHex())
        assertEquals("1903e8", Cbor.encode(Cbor.Uint(1000)).toHex())
        assertEquals("1ae9181cf3", Cbor.encode(Cbor.Uint(0xe9181cf3L)).toHex())
        assertEquals("1b0000000100000000", Cbor.encode(Cbor.Uint(1L shl 32)).toHex())
    }

    @Test
    fun reencodingDecodedItemIsIdentical() {
        val item = Cbor.Dict(
            listOf(
                Cbor.Uint(1) to Cbor.Tagged(37, Cbor.Bytes(ByteArray(16) { it.toByte() })),
                Cbor.Uint(2) to Cbor.Bool(true),
                Cbor.Uint(3) to Cbor.Arr(listOf(Cbor.Text("héllo"), Cbor.Bool(false), Cbor.Uint(7))),
            ),
        )
        val encoded = Cbor.encode(item)
        assertEquals(encoded.toHex(), Cbor.encode(Cbor.decode(encoded)).toHex())
    }

    @Test
    fun rejectsUnsupportedOrTruncatedInput() {
        val cases = mapOf(
            "indefinite-length array" to "9f00ff",
            "negative integer" to "20",
            "half-precision float" to "f90000",
            "null" to "f6",
            "truncated bytes" to "58050102",
            "reserved length encoding" to "1c",
            "trailing bytes" to "0000",
            "invalid UTF-8 text" to "61ff",
            "integer past 2^63" to "1b8000000000000000",
            "byte string length past input" to "5bffffffffffffffff00",
            "array count past input" to "9b00000000ffffffff00",
            "truncated head" to "19",
            "empty input" to "",
        )
        for ((name, hex) in cases) {
            assertFailsWith<MalformedPayloadException>(name) { Cbor.decode(Numeric.hexStringToByteArray(hex)) }
        }
    }

    @Test
    fun rejectsNestingDeeperThanEight() {
        // Nine array headers put the leaf at depth 9; eight are allowed.
        assertFailsWith<MalformedPayloadException> {
            Cbor.decode(Numeric.hexStringToByteArray("81".repeat(9) + "00"))
        }
        Cbor.decode(Numeric.hexStringToByteArray("81".repeat(8) + "00"))
    }

    @Test
    fun refusesMoreItemsThanTheCountLimitWithAReason() {
        // An array header plus 255 items is exactly the limit; one more item is refused.
        Cbor.decode(Cbor.encode(Cbor.Arr(List(ParserLimits.MAX_CBOR_ITEMS - 1) { Cbor.Uint(0) })))
        val error = assertFailsWith<MalformedPayloadException> {
            Cbor.decode(Cbor.encode(Cbor.Arr(List(ParserLimits.MAX_CBOR_ITEMS) { Cbor.Uint(0) })))
        }
        assertEquals("CBOR has more than ${ParserLimits.MAX_CBOR_ITEMS} items", error.message)
    }

    @Test
    fun stopsWhenTheDecodeBudgetIsSpent() {
        // Each clock read advances ten units, so the budget of 100 runs out after about ten items.
        var now = 0L
        val budget = DecodeBudget("CBOR", ParserLimits.MAX_CBOR_ITEMS, maxNanos = 100) { now += 10; now }
        val payload = Cbor.encode(Cbor.Arr(List(20) { Cbor.Uint(it.toLong()) }))
        val error = assertFailsWith<MalformedPayloadException> { Cbor.decode(payload, budget) }
        assertTrue(error.message!!.contains("time budget"), error.message)
    }
}
