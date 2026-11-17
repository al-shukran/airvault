package app.lensly.core.parse

import app.lensly.core.algorand.AlgoTxnVectors
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.framesOf
import com.sparrowwallet.hummingbird.UR
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import org.junit.Test

class AlgorandScanWireTest {

    @Test
    fun anAlgorandScanReplyCrossesTheWireUnchanged() {
        val payload = byteArrayOf(0xa1.toByte(), 1, 2, 3)
        val decoded = ParserWire.decodeScanReply(ParserWire.encodeScanReply(ScanReply.Algorand(payload)))
        assertContentEquals(payload, assertIs<ScanReply.Algorand>(decoded).payload)
    }

    /** A complete algo-sign-request for a transaction the sandbox can read is answered with its CBOR, across the same wire the app uses. */
    @Test
    fun aReadableAlgorandRequestIsAnsweredWithItsPayloadAcrossTheWire() {
        val payload = requestCbor(signData = pay(), signType = 1L)
        val call = ParserWire.encodeScanRequest(session = 1L, base = 0, parts = parts(payload))
        val reply = ParserWire.decodeScanReply(SandboxAnswers().answer(ParserWire.SCAN, call))
        assertContentEquals(payload, assertIs<ScanReply.Algorand>(reply).payload)
    }

    /** A transaction the decoder refuses is refused by the sandbox, and never reaches the app as a request. */
    @Test
    fun anAlgorandTransactionThatDoesNotDecodeIsRefused() {
        val payload = requestCbor(signData = byteArrayOf(0x7f, 1, 2), signType = 1L)
        assertIs<ScanReply.Refused>(InProcessParser.scan(parts(payload)))
    }

    /** A message to sign is not a transaction, so its bytes are not decoded as one, and the sandbox answers with the payload. */
    @Test
    fun anAlgorandMessageToSignIsAnsweredWithoutATransactionRead() {
        val payload = requestCbor(signData = byteArrayOf(0x7f, 1, 2), signType = 2L)
        assertContentEquals(payload, assertIs<ScanReply.Algorand>(InProcessParser.scan(parts(payload))).payload)
    }

    /** One complete UR part. The payload is small, so it is a single frame. */
    private fun parts(payload: ByteArray): List<String> =
        framesOf(UR("algo-sign-request", payload), maxFragment = 4096).take(1).toList()

    private fun pay(): ByteArray = AlgoTxnVectors.valid().single { it.name == "pay_basic" }.raw

    /** An algo-sign-request on account 0 of the path 44'/283'/0'/0/0, with the codec's fields for [signData] and [signType]. */
    private fun requestCbor(signData: ByteArray, signType: Long): ByteArray {
        val path = listOf(44L to true, 283L to true, 0L to true, 0L to false, 0L to false)
        val components = path.flatMap { (index, hardened) -> listOf(Cbor.Uint(index), Cbor.Bool(hardened)) }
        val keypath = Cbor.Tagged(304, Cbor.Dict(listOf(Cbor.Uint(1L) to Cbor.Arr(components), Cbor.Uint(2L) to Cbor.Uint(0x12121212L))))
        return Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(1L) to Cbor.Tagged(37, Cbor.Bytes(ByteArray(16) { 0x11 })),
                    Cbor.Uint(2L) to Cbor.Bytes(signData),
                    Cbor.Uint(3L) to keypath,
                    Cbor.Uint(4L) to Cbor.Text(""),
                    Cbor.Uint(5L) to Cbor.Text("lumio"),
                    Cbor.Uint(6L) to Cbor.Uint(signType),
                ),
            ),
        )
    }
}
