package app.lensly.core.parse

import app.lensly.core.evm.TypedDataParser
import app.lensly.core.evm.TypedValue
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private val REQUEST_ID = ByteArray(16) { (it + 1).toByte() }

private const val MESSAGE = """{"types":{"Mail":[{"name":"from","type":"Person"},{"name":"tags","type":"uint8[]"},""" +
    """{"name":"contents","type":"string"},{"name":"raw","type":"bytes4"},{"name":"live","type":"bool"},{"name":"delta","type":"int16"}],""" +
    """"Person":[{"name":"wallet","type":"address"}]},"primaryType":"Mail","domain":{"name":"Lensly","chainId":1},""" +
    """"message":{"from":{"wallet":"0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"},"tags":[1,2,255],"contents":"Hello, Bob!",""" +
    """"raw":"0xdeadbeef","live":true,"delta":-300}}"""

private fun requestOf(signData: ByteArray, dataType: DataType = DataType.TYPED_DATA) = EthSignRequest(
    requestId = REQUEST_ID,
    signData = signData,
    dataType = dataType,
    chainId = 1,
    derivationPath = emptyList(),
    sourceFingerprint = null,
    address = null,
    origin = "metamask",
)

class TypedDataWireTest {

    @Test
    fun aParsedTypedMessageCrossesTheWireWithEveryValue() {
        val signData = MESSAGE.toByteArray(Charsets.UTF_8)
        val original = TypedDataParser.parse(signData)
        val parsed = ParsedRequest(requestOf(signData), tx = null, call = null, typed = original)

        val decoded = assertIs<ScanReply.Complete>(ParserWire.decodeScanReply(ParserWire.encodeScanReply(ScanReply.Complete(parsed))))
        val typed = assertIs<TypedDataParser.Result.Ok>(decoded.parsed.typed).data

        assertEquals(assertIs<TypedDataParser.Result.Ok>(original).data.digest().toList(), typed.digest().toList())
        assertEquals("Mail", typed.primaryType)
        assertEquals(
            listOf("from", "tags", "contents", "raw", "live", "delta"),
            typed.types.getValue("Mail").map { it.name },
        )
        assertEquals("Hello, Bob!", (typed.message.fields.getValue("contents") as TypedValue.TextValue).value)
        assertEquals(
            java.math.BigInteger.valueOf(-300),
            (typed.message.fields.getValue("delta") as TypedValue.IntValue).value,
        )
    }

    @Test
    fun aRejectedTypedDocumentCrossesTheWireWithItsReason() {
        val signData = """{"types":{},"message":{}}""".toByteArray(Charsets.UTF_8)
        val parsed = ParsedRequest(requestOf(signData), tx = null, call = null, typed = TypedDataParser.parse(signData))

        val decoded = assertIs<ScanReply.Complete>(ParserWire.decodeScanReply(ParserWire.encodeScanReply(ScanReply.Complete(parsed))))

        assertEquals(
            "the document is missing primaryType",
            assertIs<TypedDataParser.Result.Rejected>(decoded.parsed.typed).reason,
        )
    }

    @Test
    fun aTypedResultOnARequestThatIsNotTypedDataIsRefusedByTheWire() {
        val signData = MESSAGE.toByteArray(Charsets.UTF_8)
        val parsed = ParsedRequest(
            requestOf(signData, dataType = DataType.PERSONAL_MESSAGE),
            tx = null,
            call = null,
            typed = TypedDataParser.parse(signData),
        )
        val reply = ParserWire.encodeScanReply(ScanReply.Complete(parsed))

        assertFailsWith<WireException> { ParserWire.decodeScanReply(reply) }
    }
}
