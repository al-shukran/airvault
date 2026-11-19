package app.lensly.core.parse

import app.airvault.chains.evm.evmManifest
import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.evm.signer.EvmRequestBody
import app.airvault.chains.evm.signer.EvmRequestParser
import app.airvault.core.model.ChainId
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.core.ur.PathStep
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

class EvmScanWireTest {
    /**
     * EIP-712 data at the sign-data limit. Its message text is nearly all of that, so the parsed copy in the body is as large as the data
     * itself, and the body is about twice the data limit. The APP_REQUEST wire has to carry that body whole.
     */
    @Test
    fun aNearMaxTypedDataBodyCrossesTheAppRequestWire() {
        val prefix = "{\"types\":{\"EIP712Domain\":[{\"name\":\"chainId\",\"type\":\"uint256\"}]," +
            "\"Note\":[{\"name\":\"text\",\"type\":\"string\"}]},\"primaryType\":\"Note\"," +
            "\"domain\":{\"chainId\":1},\"message\":{\"text\":\""
        val suffix = "\"}}"
        val document = (prefix + "a".repeat(ParserLimits.MAX_SIGN_DATA_BYTES - prefix.length - suffix.length) + suffix).toByteArray()
        val path = listOf(PathStep(44, true), PathStep(60, true))
        val body = EvmRequestBody.encode(
            EvmRequestParser.parsedOf(
                EthSignRequest(
                    requestId = null,
                    signData = document,
                    dataType = DataType.TYPED_DATA,
                    chainId = 1L,
                    derivationPath = path,
                    sourceFingerprint = 0x12345678,
                    address = null,
                    origin = null,
                ),
            ),
        )
        val header = RequestHeader(
            app = evmManifest.id,
            chain = ChainId("eip155:1"),
            kind = RequestKind.TYPED_DATA,
            signers = listOf(SignerRef(0x12345678, path)),
            requestId = null,
        )

        val decoded = ParserWire.decodeScanReply(ParserWire.encodeScanReply(ScanReply.App(AppRequest(header, body))))

        assertContentEquals(body, assertIs<ScanReply.App>(decoded).request.body)
    }
}
