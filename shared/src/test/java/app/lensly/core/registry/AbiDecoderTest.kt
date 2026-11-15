package app.lensly.core.registry

import app.lensly.core.parse.ParserLimits
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

private const val RECIPIENT = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
private const val ALICE = "0x1111111111111111111111111111111111111111"
private const val BOB = "0x2222222222222222222222222222222222222222"
private const val SELECTOR = "00000000"

/**
 * The decoder behind a whitelisted call. Every argument is read from the calldata behind its type, every offset, length and count is
 * checked before it is used, and a call is accepted only when its values encode back to the same bytes.
 */
class AbiDecoderTest {

    @Test
    fun anAddressIsShownInFullAsItsChecksummedValue() {
        val address = RECIPIENT.lowercase()

        assertEquals(Keys.toChecksumAddress(address), shown(WhitelistParam("to", "address"), word(address)))
    }

    @Test
    fun anAddressWithDirtyHighBytesIsRefused() {
        val dirty = "ff" + "00".repeat(11) + RECIPIENT.lowercase().removePrefix("0x")

        assertRefused(call(dirty), listOf(WhitelistParam("to", "address")), AbiDecoder.PADDING_REFUSAL)
    }

    @Test
    fun aUintIsItsExactDecimalAndHighBitsOutsideItsWidthAreRefused() {
        assertEquals("255", shown(WhitelistParam("v", "uint8"), word("ff")))
        val big = BigInteger.TEN.pow(30)
        assertEquals(big.toString(), shown(WhitelistParam("v", "uint256"), word(big.toString(16))))
        assertRefused(call(word("0100")), listOf(WhitelistParam("v", "uint8")), AbiDecoder.RANGE_REFUSAL)
    }

    @Test
    fun anIntIsItsSignedDecimalAndOutOfRangeIsRefused() {
        assertEquals("-1", shown(WhitelistParam("v", "int8"), "ff".repeat(32)))
        assertEquals("-128", shown(WhitelistParam("v", "int8"), "ff".repeat(31) + "80"))
        assertEquals("127", shown(WhitelistParam("v", "int8"), word("7f")))
        assertRefused(call(word("80")), listOf(WhitelistParam("v", "int8")), AbiDecoder.RANGE_REFUSAL)
    }

    @Test
    fun aBoolIsOnlyZeroOrOne() {
        val bool = WhitelistParam("v", "bool")

        assertEquals("false", shown(bool, word("00")))
        assertEquals("true", shown(bool, word("01")))
        assertRefused(call(word("02")), listOf(bool), AbiDecoder.BOOL_REFUSAL)
    }

    @Test
    fun bytesNIsFullHexAndNonZeroPaddingIsRefused() {
        val value = "deadbeef" + "00".repeat(28)
        assertEquals("0xdeadbeef", shown(WhitelistParam("v", "bytes4"), value))

        val padded = "deadbeef" + "ff" + "00".repeat(27)
        assertRefused(call(padded), listOf(WhitelistParam("v", "bytes4")), AbiDecoder.PADDING_REFUSAL)
    }

    @Test
    fun calldataMustBeExactlyTheSelectorAndOneWordPerStaticParam() {
        val params = listOf(WhitelistParam("v", "uint256"))

        assertRefused(byteArrayOf(0, 0, 0), params, AbiDecoder.LENGTH_REFUSAL)
        assertRefused(byteArrayOf(0, 0, 0, 0), params, AbiDecoder.LENGTH_REFUSAL)
        assertRefused(call(word("01"), word("02")), params, AbiDecoder.LENGTH_REFUSAL)
        assertEquals("1", shown(params.single(), word("01")))
    }

    @Test
    fun aDynamicBytesArgumentIsReadBehindItsOffset() {
        val params = listOf(WhitelistParam("data", "bytes"))

        val decoded = assertIs<CallDecode.Ok>(AbiDecoder.decode(call(word("20"), word("03"), dataWord("616263")), params))

        assertEquals(AbiValue.Dynamic("616263"), decoded.params.single().value)
        assertEquals("3 bytes: 0x616263", AbiRows.of(decoded.params.single()).single().second)
    }

    @Test
    fun aStringIsShownAsItsTextAndControlCharactersAreRefused() {
        val params = listOf(WhitelistParam("memo", "string"))

        assertEquals("\"hi\"", AbiRows.of(decodedOne(params.single(), call(word("20"), word("02"), dataWord("6869")))).single().second)
        assertRefused(call(word("20"), word("01"), dataWord("07")), params, AbiDecoder.TEXT_REFUSAL)
    }

    @Test
    fun anOffsetPastTheCalldataIsRefused() {
        val params = listOf(WhitelistParam("data", "bytes"))

        assertRefused(call(word("1000")), params, AbiDecoder.OFFSET_REFUSAL)
        // An offset of 2^256 - 1 does not fit in any calldata.
        assertRefused(call("ff".repeat(32)), params, AbiDecoder.OFFSET_REFUSAL)
        // An offset that lands on the head itself, with no length word behind it.
        assertRefused(call(word("20")), params, AbiDecoder.OFFSET_REFUSAL)
    }

    @Test
    fun aLengthRunningPastTheCalldataIsRefused() {
        val params = listOf(WhitelistParam("data", "bytes"))

        // A length of 2^64 - 1 bytes with no data behind it.
        assertRefused(call(word("20"), word("ff".repeat(8))), params, AbiDecoder.LENGTH_RUN_REFUSAL)
        // A length of 64 bytes with one word of data behind it.
        assertRefused(call(word("20"), word("40"), dataWord("aa")), params, AbiDecoder.LENGTH_RUN_REFUSAL)
    }

    @Test
    fun aHugeArrayCountIsRefusedBeforeAnyItemIsRead() {
        val params = listOf(WhitelistParam("values", "uint256[]"))

        // A count of 2^255 items, with no word behind the count.
        assertRefused(call(word("20"), "8" + "0".repeat(63)), params, AbiDecoder.LENGTH_RUN_REFUSAL)
        // A count one more than the words behind it.
        assertRefused(call(word("20"), word("02"), word("01")), params, AbiDecoder.LENGTH_RUN_REFUSAL)
    }

    @Test
    fun aCountOfExactlyTheWordsBehindItIsRead() {
        val params = listOf(WhitelistParam("values", "uint256[]"))

        val decoded = assertIs<CallDecode.Ok>(AbiDecoder.decode(call(word("20"), word("02"), word("01"), word("02")), params))

        assertEquals(
            listOf("values (uint256[])" to "2 items", "values[0] (uint256)" to "1", "values[1] (uint256)" to "2"),
            AbiRows.of(decoded.params.single()),
        )
    }

    @Test
    fun aCallWithMoreItemsThanTheBudgetIsRefused() {
        // uint256[512][2] holds 1027 values (the two rows, then 1024 words), one more than one call may decode.
        val params = listOf(WhitelistParam("grid", "uint256[512][2]"))

        val refused = assertIs<CallDecode.Refused>(
            AbiDecoder.decode(byteArrayOf(0, 0, 0, 0) + ByteArray(2 * 512 * 32), params),
        )

        assertTrue(refused.reason.startsWith("ABI has more than"), refused.reason)
    }

    @Test
    fun aTypeNestedDeeperThanTheLimitIsRefusedAtTheEntry() {
        val deepArrays = "uint256" + "[]".repeat(ParserLimits.MAX_ABI_DEPTH + 1)
        val deepTuples = "(".repeat(ParserLimits.MAX_ABI_DEPTH + 1) + "uint256" + ")".repeat(ParserLimits.MAX_ABI_DEPTH + 1)

        assertNull(AbiTypes.parse(deepArrays))
        assertNull(AbiTypes.parse(deepTuples))
        assertFailsWith<IllegalArgumentException> { WhitelistParam("v", deepArrays) }
        assertNotNull(AbiTypes.parse("uint256" + "[]".repeat(ParserLimits.MAX_ABI_DEPTH)))
    }

    @Test
    fun aFixedArrayLongerThanTheLimitIsRefusedAtTheEntry() {
        assertNull(AbiTypes.parse("uint256[${ParserLimits.MAX_ABI_ARRAY_LENGTH + 1}]"))
        assertNotNull(AbiTypes.parse("uint256[${ParserLimits.MAX_ABI_ARRAY_LENGTH}]"))
    }

    @Test
    fun aLayoutWithAnUnusedGapIsRefusedAsNotCanonical() {
        val params = listOf(WhitelistParam("a", "bytes"), WhitelistParam("b", "bytes"))
        // Both offsets are valid and both values are empty, but the second value sits one word further on than the canonical
        // encoding puts it, so the calldata is not the one the values encode to.
        val gapped = call(word("40"), word("80"), word("00"), word("00"), word("00"))

        assertRefused(gapped, params, AbiDecoder.CANONICAL_REFUSAL)
    }

    @Test
    fun aDynamicCallEncodesBackToTheValuesItDecodesFrom() {
        val decoded = listOf(
            DecodedParam(WhitelistParam("amount", "uint256"), AbiValue.Word(word("07"))),
            DecodedParam(
                WhitelistParam("path", "address[]"),
                AbiValue.Items(listOf(AbiValue.Word(word(ALICE)), AbiValue.Word(word(BOB)))),
            ),
            DecodedParam(WhitelistParam("memo", "bytes"), AbiValue.Dynamic("abcdef")),
            DecodedParam(
                WhitelistParam("route", "(address,bytes)[]"),
                AbiValue.Items(listOf(AbiValue.Items(listOf(AbiValue.Word(word(ALICE)), AbiValue.Dynamic("01"))))),
            ),
        )
        val args = assertNotNull(AbiDecoder.encode(decoded))

        val back = assertIs<CallDecode.Ok>(AbiDecoder.decode(byteArrayOf(0, 0, 0, 0) + args, decoded.map { it.param }))

        assertEquals(decoded, back.params)
    }

    @Test
    fun mutatedCalldataIsRefusedOrDecodesToItsOwnEncoding() {
        val params = listOf(
            WhitelistParam("amount", "uint256"),
            WhitelistParam("path", "address[]"),
            WhitelistParam("memo", "bytes"),
            WhitelistParam("route", "(address,bytes)[]"),
        )
        val valid = byteArrayOf(0, 0, 0, 0) + assertNotNull(
            AbiDecoder.encode(
                listOf(
                    DecodedParam(params[0], AbiValue.Word(word("07"))),
                    DecodedParam(params[1], AbiValue.Items(listOf(AbiValue.Word(word(ALICE))))),
                    DecodedParam(params[2], AbiValue.Dynamic("abcdef")),
                    DecodedParam(params[3], AbiValue.Items(listOf(AbiValue.Items(listOf(AbiValue.Word(word(BOB)), AbiValue.Dynamic("01")))))),
                ),
            ),
        )
        val random = Random(20261116)

        // Each input is the valid call with a few bytes changed, or cut short. Decoding must refuse it or accept it as the one
        // encoding of its values, and must never throw.
        repeat(2_000) {
            var input = valid.copyOf()
            repeat(1 + random.nextInt(4)) { input[random.nextInt(input.size)] = random.nextInt(256).toByte() }
            if (random.nextInt(4) == 0) input = input.copyOf(random.nextInt(input.size + 1))
            when (val result = AbiDecoder.decode(input, params)) {
                is CallDecode.Refused -> Unit
                is CallDecode.Ok -> assertContentEquals(input.copyOfRange(4, input.size), assertNotNull(AbiDecoder.encode(result.params)))
            }
        }
    }

    private fun decodedOne(param: WhitelistParam, calldata: ByteArray): DecodedParam =
        assertIs<CallDecode.Ok>(AbiDecoder.decode(calldata, listOf(param))).params.single()

    private fun shown(param: WhitelistParam, word: String): String =
        AbiRows.of(decodedOne(param, call(word))).single().second

    private fun assertRefused(calldata: ByteArray, params: List<WhitelistParam>, reason: String) {
        assertEquals(reason, assertIs<CallDecode.Refused>(AbiDecoder.decode(calldata, params)).reason)
    }

    /** The selector, then each word. Every word is 32 bytes, so it is 64 hex digits. */
    private fun call(vararg words: String): ByteArray {
        assertTrue(words.all { it.length == 64 }, "every word is 64 hex digits")
        return Numeric.hexStringToByteArray(SELECTOR + words.joinToString(""))
    }

    /** [hex] as a right-aligned number in one 32-byte word, without the 0x prefix. */
    private fun word(hex: String): String = hex.removePrefix("0x").padStart(64, '0')

    /** [hex] as the start of one 32-byte word, which is how bytes data is laid out. */
    private fun dataWord(hex: String): String = hex.padEnd(64, '0')
}
