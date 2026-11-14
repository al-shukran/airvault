package app.lensly.core.parse

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

class BitcoinScanWireTest {
    @Test
    fun aBitcoinScanReplyCrossesTheWireUnchanged() {
        val psbt = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte(), 1, 2, 3)
        val decoded = ParserWire.decodeScanReply(ParserWire.encodeScanReply(ScanReply.Bitcoin(psbt)))
        assertContentEquals(psbt, assertIs<ScanReply.Bitcoin>(decoded).psbt)
    }
}
