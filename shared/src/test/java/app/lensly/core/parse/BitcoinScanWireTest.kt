package app.lensly.core.parse

import app.airvault.chains.bitcoin.signer.bitcoinManifest
import app.airvault.core.model.ChainId
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BitcoinScanWireTest {
    @Test
    fun aBitcoinAppRequestCrossesTheWireUnchanged() {
        val body = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte(), 1, 2, 3)
        val header = RequestHeader(
            app = bitcoinManifest.id,
            chain = ChainId("bip122:000000000019d6689c085ae165831e93"),
            kind = RequestKind.TRANSACTION,
            signers = emptyList(),
            requestId = null,
        )
        val reply = ScanReply.App(AppRequest(header, body))
        val decoded = assertIs<ScanReply.App>(ParserWire.decodeScanReply(ParserWire.encodeScanReply(reply)))
        assertEquals(header, decoded.request.header)
        assertContentEquals(body, decoded.request.body)
    }
}
