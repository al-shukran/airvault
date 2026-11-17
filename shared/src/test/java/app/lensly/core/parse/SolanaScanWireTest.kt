package app.lensly.core.parse

import app.lensly.core.solana.SolanaVectors
import app.lensly.core.solana.offchainEnvelope
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.framesOf
import com.sparrowwallet.hummingbird.UR
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import org.junit.Test

class SolanaScanWireTest {

    @Test
    fun aSolanaScanReplyCrossesTheWireUnchanged() {
        val payload = byteArrayOf(0xa1.toByte(), 1, 2, 3)
        val decoded = ParserWire.decodeScanReply(ParserWire.encodeScanReply(ScanReply.Solana(payload)))
        assertContentEquals(payload, assertIs<ScanReply.Solana>(decoded).payload)
    }

    /** A complete sol-sign-request for a transaction the sandbox can read is answered with its CBOR, across the same wire the app uses. */
    @Test
    fun aReadableSolanaRequestIsAnsweredWithItsPayloadAcrossTheWire() {
        val payload = requestCbor(signData = SolanaVectors["legacy-sol-transfer"], signType = 1L)
        val reply = ParserWire.decodeScanReply(answer(InProcessParser, ParserWire.SCAN, ParserWire.encodeScanRequest(parts(payload))))
        assertContentEquals(payload, assertIs<ScanReply.Solana>(reply).payload)
    }

    /** A transaction whose message does not parse is refused by the sandbox, and never reaches the app as a request. */
    @Test
    fun aSolanaTransactionWhoseMessageDoesNotParseIsRefused() {
        val payload = requestCbor(signData = byteArrayOf(0x7f, 1, 2), signType = 1L)
        assertIs<ScanReply.Refused>(InProcessParser.scan(parts(payload)))
    }

    /**
     * A message to sign is an off-chain message, so its bytes are not parsed as a transaction. A valid v0 envelope is answered with its
     * payload, and the sandbox does not read it as a transaction.
     */
    @Test
    fun aSolanaMessageToSignIsAnsweredWithoutAMessageParse() {
        val payload = requestCbor(signData = offchainEnvelope("hello".toByteArray()), signType = 2L)
        assertContentEquals(payload, assertIs<ScanReply.Solana>(InProcessParser.scan(parts(payload))).payload)
    }

    /** One complete UR part, uppercase as the wallet shows it. The payload is small, so it is a single frame. */
    private fun parts(payload: ByteArray): List<String> =
        framesOf(UR("sol-sign-request", payload), maxFragment = 4096).take(1).toList()

    /** A sol-sign-request on account 0 of the path 44'/501'/0', with the codec's fields for [signData] and [signType]. */
    private fun requestCbor(signData: ByteArray, signType: Long): ByteArray {
        val components = listOf(44L, 501L, 0L).flatMap { listOf(Cbor.Uint(it), Cbor.Bool(true)) }
        val keypath = Cbor.Tagged(304, Cbor.Dict(listOf(Cbor.Uint(1L) to Cbor.Arr(components), Cbor.Uint(2L) to Cbor.Uint(0x12121212L))))
        return Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(1L) to Cbor.Tagged(37, Cbor.Bytes(ByteArray(16) { 0x11 })),
                    Cbor.Uint(2L) to Cbor.Bytes(signData),
                    Cbor.Uint(3L) to keypath,
                    Cbor.Uint(5L) to Cbor.Text("solflare"),
                    Cbor.Uint(6L) to Cbor.Uint(signType),
                ),
            ),
        )
    }
}
