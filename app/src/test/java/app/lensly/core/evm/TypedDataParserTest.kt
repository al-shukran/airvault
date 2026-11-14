package app.lensly.core.evm

import java.math.BigInteger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The address of Bob in the standard's Mail example, with its EIP-55 checksum. */
private const val MAIL_CHECKSUM_OF_BOB = "0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"

/** A small typed message: one string member, a derived EIP712Domain with a chain ID of 1. */
private fun doc(
    types: String = """{"Mail":[{"name":"contents","type":"string"}]}""",
    primary: String = "Mail",
    domain: String = """{"chainId":1}""",
    message: String = """{"contents":"hi"}""",
): ByteArray = """{"types":$types,"primaryType":"$primary","domain":$domain,"message":$message}""".toByteArray(Charsets.UTF_8)

private fun parse(document: ByteArray): TypedDataParser.Result = TypedDataParser.parse(document)

private fun refusal(document: ByteArray): String = assertIs<TypedDataParser.Result.Rejected>(parse(document)).reason

private fun refusal(json: String): String = refusal(json.toByteArray(Charsets.UTF_8))

private val MAIL_JSON = """{"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},""" +
    """{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],""" +
    """"Person":[{"name":"name","type":"string"},{"name":"wallet","type":"address"}],""" +
    """"Mail":[{"name":"from","type":"Person"},{"name":"to","type":"Person"},{"name":"contents","type":"string"}]},""" +
    """"primaryType":"Mail","domain":{"name":"Ether Mail","version":"1","chainId":1,""" +
    """"verifyingContract":"0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"},""" +
    """"message":{"from":{"name":"Cow","wallet":"0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"},""" +
    """"to":{"name":"Bob","wallet":"$MAIL_CHECKSUM_OF_BOB"},"contents":"Hello, Bob!"}}"""

class TypedDataParserTest {

    @Test
    fun theStandardsMailExampleParsesWithItsDomainAndMessage() {
        val data = assertIs<TypedDataParser.Result.Ok>(parse(MAIL_JSON.toByteArray(Charsets.UTF_8))).data

        assertEquals("Mail", data.primaryType)
        assertEquals(BigInteger.ONE, data.domainChainId)
        assertEquals("Hello, Bob!", (data.message.fields.getValue("contents") as TypedValue.TextValue).value)
    }

    @Test
    fun aDomainWithoutADeclaredTypeGetsTheStandardMembersItNames() {
        val data = assertIs<TypedDataParser.Result.Ok>(parse(doc(domain = """{"chainId":1,"name":"Lensly"}"""))).data

        assertEquals(
            listOf(TypedField("name", "string"), TypedField("chainId", "uint256")),
            data.types.getValue(EIP712_DOMAIN),
        )
    }

    @Test
    fun aUtf8TextWithAnEscapedSurrogatePairParses() {
        val document = doc(message = """{"contents":"😀 é"}""")

        val data = assertIs<TypedDataParser.Result.Ok>(parse(document)).data

        assertEquals("😀 é", (data.message.fields.getValue("contents") as TypedValue.TextValue).value)
    }

    @Test
    fun aDocumentThatIsNotUtf8IsRefused() {
        assertEquals("the document is not valid UTF-8", refusal(byteArrayOf(0xFF.toByte(), 0x7B)))
    }

    @Test
    fun aDocumentThatIsNotAnObjectIsRefused() {
        assertTrue(refusal("[]").contains("is not a JSON object"))
    }

    @Test
    fun textAfterTheDocumentIsRefused() {
        assertTrue(refusal(String(doc(), Charsets.UTF_8) + " x").contains("text after its end"))
    }

    @Test
    fun aDuplicateMemberNameIsRefused() {
        val json = """{"types":{},"types":{},"primaryType":"Mail","domain":{"chainId":1},"message":{}}"""

        assertTrue(refusal(json).contains("appears twice"))
    }

    @Test
    fun anUnknownTopLevelMemberIsRefused() {
        val json = String(doc(), Charsets.UTF_8).replace("\"message\"", "\"extra\":1,\"message\"")

        assertTrue(refusal(json).contains("unknown member extra"))
    }

    @Test
    fun aMissingDomainIsRefused() {
        val json = """{"types":{"Mail":[]},"primaryType":"Mail","message":{}}"""

        assertTrue(refusal(json).contains("missing domain"))
    }

    @Test
    fun aDomainWithoutAChainIdIsRefused() {
        assertEquals("The typed data names no chain ID in its domain", refusal(doc(domain = """{"name":"Lensly"}""")))
    }

    @Test
    fun aDeclaredDomainWithoutAChainIdIsRefused() {
        val types = """{"EIP712Domain":[{"name":"name","type":"string"}],"Mail":[{"name":"contents","type":"string"}]}"""

        assertEquals("The typed data names no chain ID in its domain", refusal(doc(types = types, domain = """{"name":"x"}""")))
    }

    @Test
    fun aChainIdOfZeroIsRefused() {
        assertTrue(refusal(doc(domain = """{"chainId":0}""")).contains("outside the range Lensly reads"))
    }

    @Test
    fun aChainIdThatIsNotANumberIsRefused() {
        assertTrue(refusal(doc(domain = """{"chainId":"abc"}""")).contains("is not a number"))
    }

    @Test
    fun aDomainMemberTheStandardDoesNotNameIsRefused() {
        assertTrue(refusal(doc(domain = """{"chainId":1,"owner":"0x"}""")).contains("domain member owner is not an EIP-712 domain member"))
    }

    @Test
    fun aPrimaryTypeThatIsNotDeclaredIsRefused() {
        assertTrue(refusal(doc(primary = "Nope")).contains("primaryType Nope is not one of the types"))
    }

    @Test
    fun aMemberOfAnUnknownTypeIsRefused() {
        val types = """{"Mail":[{"name":"contents","type":"Foo"}]}"""

        assertTrue(refusal(doc(types = types, message = "{}")).contains("has unknown type Foo"))
    }

    @Test
    fun aStructThatContainsItselfIsRefusedBeforeAnyValueIsRead() {
        val types = """{"A":[{"name":"b","type":"B"}],"B":[{"name":"a","type":"A"}]}"""

        assertTrue(refusal(doc(types = types, primary = "A", message = "{}")).contains("contains itself"))
    }

    @Test
    fun aMissingMessageMemberIsRefused() {
        assertTrue(refusal(doc(message = "{}")).contains("message is missing contents"))
    }

    @Test
    fun aMessageMemberTheTypeDoesNotDeclareIsRefused() {
        assertTrue(refusal(doc(message = """{"contents":"hi","extra":1}""")).contains("unknown member extra"))
    }

    @Test
    fun anAddressThatIsNotTwentyBytesIsRefused() {
        val json = MAIL_JSON.replace(MAIL_CHECKSUM_OF_BOB, "0x1234")

        assertTrue(refusal(json).contains("message.to.wallet is not an address"))
    }

    @Test
    fun aMixedCaseAddressWithAWrongChecksumIsRefused() {
        // Changing the case of one letter breaks the EIP-55 checksum, and the address keeps its 40 hex digits.
        val json = MAIL_JSON.replace(MAIL_CHECKSUM_OF_BOB, MAIL_CHECKSUM_OF_BOB.replaceFirst('b', 'B'))
        val reason = refusal(json)

        assertEquals("message.to.wallet has a wrong address checksum", reason)
    }

    @Test
    fun aValueOutsideItsIntegerTypeIsRefused() {
        val types = """{"Mail":[{"name":"n","type":"uint8"}]}"""

        assertTrue(refusal(doc(types = types, message = """{"n":256}""")).contains("is out of range for uint8"))
        assertEquals(
            "message.n is out of range for uint8",
            refusal(doc(types = types, message = """{"n":"256"}""")),
        )
    }

    @Test
    fun aNegativeUnsignedValueIsRefused() {
        val types = """{"Mail":[{"name":"n","type":"uint256"}]}"""

        assertTrue(refusal(doc(types = types, message = """{"n":-1}""")).contains("must not be negative"))
    }

    @Test
    fun aFractionOrExponentIsNotAWholeNumber() {
        val types = """{"Mail":[{"name":"n","type":"uint256"}]}"""

        assertTrue(refusal(doc(types = types, message = """{"n":1.5}""")).contains("must be a whole number"))
        assertTrue(refusal(doc(types = types, message = """{"n":1e3}""")).contains("must be a whole number"))
    }

    @Test
    fun aBytesNValueOfTheWrongLengthIsRefused() {
        val types = """{"Mail":[{"name":"b","type":"bytes2"}]}"""

        assertTrue(refusal(doc(types = types, message = """{"b":"0xaabbcc"}""")).contains("must be 2 bytes"))
        assertIs<TypedDataParser.Result.Ok>(parse(doc(types = types, message = """{"b":"0xaabb"}""")))
    }

    @Test
    fun aBoolWrittenAsTextIsRefused() {
        val types = """{"Mail":[{"name":"flag","type":"bool"}]}"""

        assertTrue(refusal(doc(types = types, message = """{"flag":"true"}""")).contains("must be true or false"))
    }

    @Test
    fun aFixedArrayWithTheWrongNumberOfItemsIsRefused() {
        val types = """{"Mail":[{"name":"xs","type":"uint256[2]"}]}"""

        assertTrue(refusal(doc(types = types, message = """{"xs":[1]}""")).contains("must hold 2 items"))
    }

    @Test
    fun aDocumentNestedDeeperThanTheLimitIsRefused() {
        val nested = "[".repeat(10) + "1" + "]".repeat(10)

        assertTrue(refusal(doc(message = """{"contents":$nested}""")).contains("nests deeper than 8 levels"))
    }

    @Test
    fun aDocumentWithMoreValuesThanTheLimitIsRefused() {
        val types = """{"Mail":[{"name":"xs","type":"uint256[]"}]}"""
        val items = List(4100) { "0" }.joinToString(",")

        assertTrue(refusal(doc(types = types, message = """{"xs":[$items]}""")).contains("holds more than 4096 values"))
    }

    @Test
    fun aRawControlCharacterInAStringIsRefused() {
        val json = "{\"types\":{\"Mail\":[]},\"primaryType\":\"Mail\",\"domain\":{\"chainId\":1},\"message\":{\"x\":\"a\nb\"}}"

        assertTrue(refusal(json).contains("control character"))
    }

    @Test
    fun aLoneSurrogateEscapeIsRefused() {
        assertTrue(refusal(doc(message = """{"contents":"\uD800"}""")).contains("a surrogate is not paired"))
    }

    @Test
    fun anUnknownEscapeIsRefused() {
        assertTrue(refusal(doc(message = """{"contents":"\x"}""")).contains("unknown escape"))
    }

    @Test
    fun anEmptyBytesValueIsAccepted() {
        val types = """{"Mail":[{"name":"b","type":"bytes"}]}"""

        assertIs<TypedDataParser.Result.Ok>(parse(doc(types = types, message = """{"b":"0x"}""")))
        assertTrue(refusal(doc(types = types, message = """{"b":"0xzz"}""")).contains("is not hex bytes"))
    }

    @Test
    fun aNumberGivenAsAStringWithSignIsAcceptedOnlyForSignedTypes() {
        val signed = """{"Mail":[{"name":"n","type":"int8"}]}"""
        val unsigned = """{"Mail":[{"name":"n","type":"uint8"}]}"""

        val data = assertIs<TypedDataParser.Result.Ok>(parse(doc(types = signed, message = """{"n":"-5"}""")))
        val value = data.data.message.fields.getValue("n") as TypedValue.IntValue
        assertEquals(BigInteger.valueOf(-5), value.value)
        assertTrue(refusal(doc(types = unsigned, message = """{"n":"-5"}""")).contains("must not be negative"))
    }
}
