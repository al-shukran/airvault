package app.airvault.chains.evm.signer

import app.airvault.core.signer.ParseOutcome
import app.lensly.core.parse.ParserLimits
import app.lensly.core.parse.WireException
import app.lensly.core.ur.PathStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class EvmRequestParserTest {
    private val request = EthSignRequest(
        requestId = null,
        signData = "hello".toByteArray(),
        dataType = DataType.PERSONAL_MESSAGE,
        chainId = null,
        derivationPath = listOf(PathStep(44, true), PathStep(60, true)),
        sourceFingerprint = 0x12345678,
        address = null,
        origin = "sample.example",
    )

    @Test
    fun aParsedRequestReadsBackFromItsBodyWithEveryField() {
        val decoded = EvmRequestBody.decode(EvmRequestBody.encode(ParsedRequest(request, tx = null, call = null)))

        assertEquals(request.signData.toList(), decoded.request.signData.toList())
        assertEquals(DataType.PERSONAL_MESSAGE, decoded.request.dataType)
        assertEquals(request.derivationPath, decoded.request.derivationPath)
        assertEquals(request.sourceFingerprint, decoded.request.sourceFingerprint)
        assertEquals("sample.example", decoded.request.origin)
        assertEquals(null, decoded.tx)
    }

    @Test
    fun aBodyCutShortIsRefusedNotRead() {
        val body = EvmRequestBody.encode(ParsedRequest(request, tx = null, call = null))

        assertFailsWith<WireException> { EvmRequestBody.decode(body.copyOf(body.size - 1)) }
    }

    @Test
    fun aUrOfAnotherTypeIsRefused() {
        val outcome = EvmRequestParser.parse("crypto-psbt", ByteArray(1), ParserLimits())

        assertEquals("UR type crypto-psbt is not eth-sign-request", assertIs<ParseOutcome.Refused>(outcome).reason)
    }

    @Test
    fun aPayloadOverTheRequestLimitIsRefusedBeforeItIsRead() {
        val payload = ByteArray(EthSignRequest.MAX_PAYLOAD_BYTES + 1)

        val outcome = EvmRequestParser.parse(EthSignRequest.UR_TYPE, payload, ParserLimits())

        assertIs<ParseOutcome.Refused>(outcome)
    }
}
