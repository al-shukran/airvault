package app.lensly.core.parse

import app.airvault.chains.solana.SOLANA_MAINNET
import app.airvault.chains.solana.signer.SolanaOffchainMessage
import app.airvault.chains.solana.solanaManifest
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.core.solana.SolanaVectors
import app.lensly.core.solana.offchainEnvelope
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.framesOf
import com.sparrowwallet.hummingbird.UR
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SolanaScanWireTest {

    @Test
    fun aSolanaAppRequestCrossesTheWireUnchanged() {
        val request = AppRequest(headerOf(RequestKind.TRANSACTION), body = byteArrayOf(0xa1.toByte(), 1, 2, 3))
        val decoded = ParserWire.decodeScanReply(ParserWire.encodeScanReply(ScanReply.App(request)))
        assertSameRequest(request, assertIs<ScanReply.App>(decoded).request)
    }

    /** A complete sol-sign-request for a transaction the sandbox can read is answered with its CBOR, across the same wire the app uses. */
    @Test
    fun aReadableSolanaRequestIsAnsweredWithItsPayloadAcrossTheWire() {
        val payload = requestCbor(signData = SolanaVectors["legacy-sol-transfer"], signType = 1L)
        val call = ParserWire.encodeScanRequest(session = 1L, base = 0, parts = parts(payload))
        val reply = ParserWire.decodeScanReply(SandboxAnswers().answer(ParserWire.SCAN, call))
        assertSameRequest(AppRequest(headerOf(RequestKind.TRANSACTION), payload), assertIs<ScanReply.App>(reply).request)
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
        val request = assertIs<ScanReply.App>(InProcessParser.scan(parts(payload))).request
        assertSameRequest(AppRequest(headerOf(RequestKind.MESSAGE), payload), request)
        assertEquals(SOLANA_MAINNET, request.header.chain)
    }

    /**
     * A transaction sent as a message to sign is refused. A message must be an off-chain message, which begins with the signing domain
     * that no transaction message begins with, so no transaction is ever signed as a message. The refusal names the request it read.
     */
    @Test
    fun aSolanaTransactionSentAsAMessageToSignIsRefused() {
        val payload = requestCbor(signData = SolanaVectors["legacy-sol-transfer"], signType = 2L)
        val refused = assertIs<ScanReply.Refused>(InProcessParser.scan(parts(payload)))
        assertEquals(SolanaOffchainMessage.NOT_OFF_CHAIN, refused.reason)
        assertSameRequest(AppRequest(headerOf(RequestKind.MESSAGE), payload), assertNotNull(refused.request))
    }

    /** One complete UR part, uppercase as the wallet shows it. The payload is small, so it is a single frame. */
    private fun parts(payload: ByteArray): List<String> =
        framesOf(UR("sol-sign-request", payload), maxFragment = 4096).take(1).toList()

    /** The header that [requestCbor] describes: the Solana app, mainnet-beta (it names no cluster), [kind], and the one signer it names. */
    private fun headerOf(kind: RequestKind) = RequestHeader(
        app = solanaManifest.id,
        chain = SOLANA_MAINNET,
        kind = kind,
        signers = listOf(SignerRef(0x12121212, listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true)))),
        requestId = ByteArray(16) { 0x11 },
    )

    /** Asserts that [actual] has the header and the body of [expected], with the byte arrays compared by content. */
    private fun assertSameRequest(expected: AppRequest, actual: AppRequest) {
        assertEquals(expected.header.app, actual.header.app)
        assertEquals(expected.header.chain, actual.header.chain)
        assertEquals(expected.header.kind, actual.header.kind)
        assertEquals(expected.header.signers, actual.header.signers)
        assertContentEquals(expected.header.requestId, actual.header.requestId)
        assertContentEquals(expected.body, actual.body)
    }

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
