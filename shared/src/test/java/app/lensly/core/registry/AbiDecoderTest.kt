package app.lensly.core.registry

import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

private const val RECIPIENT = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"

/** The static decoder behind a whitelisted call: every argument is one full word, and nothing hidden is accepted. */
class StaticAbiDecoderTest {

    @Test
    fun anAddressIsShownInFullAsItsChecksummedValue() {
        val address = RECIPIENT.lowercase()
        val value = decoded(WhitelistParam("to", "address"), word(address.removePrefix("0x")))

        assertEquals(Keys.toChecksumAddress(address), value)
    }

    @Test
    fun anAddressWithDirtyHighBytesIsRefused() {
        val dirty = "ff" + RECIPIENT.lowercase().removePrefix("0x")

        assertIs<StaticDecode.Refused>(StaticAbiDecoder.decode(call(dirty), listOf(WhitelistParam("to", "address"))))
    }

    @Test
    fun aUintIsItsExactDecimalAndHighBitsOutsideItsWidthAreRefused() {
        assertEquals("255", decoded(WhitelistParam("v", "uint8"), word("ff")))
        assertEquals(BigInteger.TEN.pow(30).toString(), decoded(WhitelistParam("v", "uint256"), word(BigInteger.TEN.pow(30).toString(16))))
        assertIs<StaticDecode.Refused>(StaticAbiDecoder.decode(call(word("0100")), listOf(WhitelistParam("v", "uint8"))))
    }

    @Test
    fun anIntIsItsSignedDecimalAndOutOfRangeIsRefused() {
        assertEquals("-1", decoded(WhitelistParam("v", "int8"), word("ff".repeat(32))))
        assertEquals("-128", decoded(WhitelistParam("v", "int8"), "ff".repeat(31) + "80"))
        assertEquals("127", decoded(WhitelistParam("v", "int8"), word("7f")))
        assertIs<StaticDecode.Refused>(StaticAbiDecoder.decode(call(word("80")), listOf(WhitelistParam("v", "int8"))))
    }

    @Test
    fun aBoolIsOnlyZeroOrOne() {
        assertEquals("false", decoded(WhitelistParam("v", "bool"), word("00")))
        assertEquals("true", decoded(WhitelistParam("v", "bool"), word("01")))
        assertIs<StaticDecode.Refused>(StaticAbiDecoder.decode(call(word("02")), listOf(WhitelistParam("v", "bool"))))
    }

    @Test
    fun bytesNIsFullHexAndNonZeroPaddingIsRefused() {
        val value = "deadbeef" + "00".repeat(28)
        assertEquals("0xdeadbeef", decoded(WhitelistParam("v", "bytes4"), value))

        val padded = "deadbeef" + "ff" + "00".repeat(27)
        assertIs<StaticDecode.Refused>(StaticAbiDecoder.decode(call(padded), listOf(WhitelistParam("v", "bytes4"))))
    }

    @Test
    fun calldataMustBeExactlyTheSelectorAndOneWordPerParam() {
        val params = listOf(WhitelistParam("v", "uint256"))

        assertIs<StaticDecode.Refused>(StaticAbiDecoder.decode(byteArrayOf(0, 0, 0, 0), params))
        assertIs<StaticDecode.Refused>(StaticAbiDecoder.decode(call(word("01"), word("02")), params))
        assertEquals("1", decoded(params.single(), word("01")))
    }

    private fun decoded(param: WhitelistParam, word: String): String {
        val ok = assertIs<StaticDecode.Ok>(StaticAbiDecoder.decode(call(word), listOf(param)))
        return ok.params.single().value
    }

    /** The selector bytes, then each word as 32 bytes. */
    private fun call(vararg words: String): ByteArray =
        byteArrayOf(0, 0, 0, 0) + words.map { Numeric.hexStringToByteArray(it.padStart(64, '0')) }
            .fold(ByteArray(0)) { all, bytes -> all + bytes }

    /** [hex] as a right-aligned 32-byte word, without the 0x prefix. */
    private fun word(hex: String): String = hex.removePrefix("0x").padStart(64, '0')
}
