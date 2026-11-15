package app.lensly.core.registry

import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import org.web3j.utils.Numeric

class CallEncodingTest {
    private val alice = "0x1111111111111111111111111111111111111111"
    private val bob = "0x2222222222222222222222222222222222222222"

    private fun word(hex: String): String = hex.removePrefix("0x").padStart(64, '0')

    private fun calldata(selector: String, vararg words: String): ByteArray =
        Numeric.hexStringToByteArray("0x" + selector + words.joinToString(""))

    @Test
    fun everyCallTypeEncodesBackToTheCalldataItDecodes() {
        val calls = listOf(
            calldata("a9059cbb", word(alice), word("0x64")),
            calldata("095ea7b3", word(alice), word("0x" + "ff".repeat(32))),
            calldata("23b872dd", word(alice), word(bob), word("0x01")),
            calldata("39509351", word(alice), word("0x01")),
            calldata("a457c2d7", word(alice), word("0x01")),
            calldata("a22cb465", word(alice), word("0x01")),
            calldata("42842e0e", word(alice), word(bob), word("0x07")),
        )

        for (data in calls) {
            assertContentEquals(data, assertNotNull(ContractCall.decode(data)).encode())
        }
    }

    @Test
    fun aTamperedRecipientOrAmountDoesNotEncodeToTheSignedCalldata() {
        val signed = calldata("a9059cbb", word(alice), word("0x64"))

        assertFalse(signed.contentEquals(ContractCall.Transfer(bob, BigInteger.valueOf(100)).encode()))
        assertFalse(signed.contentEquals(ContractCall.Transfer(alice, BigInteger.valueOf(101)).encode()))
    }

    @Test
    fun anAddressThatIsNotTwentyBytesHasNoEncoding() {
        assertNull(ContractCall.Transfer("0x1234", BigInteger.ONE).encode())
    }

    @Test
    fun staticArgumentsEncodeBackToTheWordsTheyDecodeFrom() {
        val params = listOf(
            WhitelistParam("owner", "address"),
            WhitelistParam("amount", "uint256"),
            WhitelistParam("flag", "bool"),
            WhitelistParam("delta", "int24"),
            WhitelistParam("salt", "bytes32"),
        )
        val data = calldata("12345678", word(alice), word("0x3e8"), word("0x1"), "f".repeat(62) + "fb", "33".repeat(32))
        val decoded = assertIs<CallDecode.Ok>(AbiDecoder.decode(data, params)).params

        assertContentEquals(data.copyOfRange(4, data.size), assertNotNull(AbiDecoder.encode(decoded)))
    }

    @Test
    fun aValueThatDoesNotFitItsTypeHasNoEncoding() {
        val small = WhitelistParam("v", "uint8")
        val owner = WhitelistParam("owner", "address")
        val highBytes = "ff" + word(alice).drop(2)

        assertNull(AbiDecoder.encode(listOf(DecodedParam(small, AbiValue.Word(word("0x100")))))), "a uint8 wider than 8 bits")
        assertNull(AbiDecoder.encode(listOf(DecodedParam(owner, AbiValue.Word(highBytes)))), "an address with high bytes")
        assertNull(AbiDecoder.encode(listOf(DecodedParam(owner, AbiValue.Dynamic("00")))), "a dynamic value for a static type")
    }

    @Test
    fun aValueWithTheWrongShapeHasNoEncoding() {
        val items = WhitelistParam("path", "address[2]")

        assertNull(AbiDecoder.encode(listOf(DecodedParam(items, AbiValue.Items(listOf(AbiValue.Word(word(alice))))))), "one item of two")
    }
}
