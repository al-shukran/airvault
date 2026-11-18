package app.lensly.core.parse

import app.airvault.chains.algorand.algorandManifest
import app.airvault.core.model.ChainId
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.core.algorand.AlgoTxnVectors
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.framesOf
import com.sparrowwallet.hummingbird.UR
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class AlgorandScanWireTest {

    @Test
    fun anAppRequestCrossesTheWireUnchanged() {
        val request = AppRequest(headerOf(TESTNET, RequestKind.TRANSACTION), body = byteArrayOf(0xa1.toByte(), 1, 2, 3))
        val decoded = ParserWire.decodeScanReply(ParserWire.encodeScanReply(ScanReply.App(request)))
        assertSameRequest(request, assertIs<ScanReply.App>(decoded).request)
    }

    /** A complete algo-sign-request for a transaction the sandbox can read is answered with its header and CBOR, across the same wire the app uses. */
    @Test
    fun aReadableAlgorandTransactionIsAnsweredWithItsHeaderAcrossTheWire() {
        val payload = requestCbor(signData = pay("pay_basic"), signType = 1L)
        val call = ParserWire.encodeScanRequest(session = 1L, base = 0, parts = parts(payload))
        val reply = ParserWire.decodeScanReply(SandboxAnswers().answer(ParserWire.SCAN, call))
        assertSameRequest(AppRequest(headerOf(TESTNET, RequestKind.TRANSACTION), payload), assertIs<ScanReply.App>(reply).request)
    }

    /** A transaction names its chain by the genesis hash it carries, so a transaction on mainnet is answered as mainnet. */
    @Test
    fun aTransactionNamesTheChainOfItsGenesisHash() {
        val payload = requestCbor(signData = pay("axfer_mainnet_usdc"), signType = 1L)
        assertEquals(MAINNET, assertIs<ScanReply.App>(InProcessParser.scan(parts(payload))).request.header.chain)
    }

    /** A message carries no genesis hash, and the request carries no network, so a message names the Algorand mainnet. */
    @Test
    fun aMessageToSignNamesTheAlgorandMainnet() {
        val payload = requestCbor(signData = byteArrayOf(0x7f, 1, 2), signType = 2L)
        val request = assertIs<ScanReply.App>(InProcessParser.scan(parts(payload))).request
        assertSameRequest(AppRequest(headerOf(MAINNET, RequestKind.MESSAGE), payload), request)
    }

    /** A transaction the decoder refuses is refused by the sandbox, and never reaches the app as a request. */
    @Test
    fun anAlgorandTransactionThatDoesNotDecodeIsRefused() {
        val payload = requestCbor(signData = byteArrayOf(0x7f, 1, 2), signType = 1L)
        assertIs<ScanReply.Refused>(InProcessParser.scan(parts(payload)))
    }

    /** One complete UR part. The payload is small, so it is a single frame. */
    private fun parts(payload: ByteArray): List<String> =
        framesOf(UR("algo-sign-request", payload), maxFragment = 4096).take(1).toList()

    private fun pay(name: String): ByteArray = AlgoTxnVectors.valid().single { it.name == name }.raw

    /** The header that [requestCbor] describes: the Algorand app, [chain] and [kind], the one signer it names, and its request id. */
    private fun headerOf(chain: ChainId, kind: RequestKind) = RequestHeader(
        app = algorandManifest.id,
        chain = chain,
        kind = kind,
        signers = listOf(SignerRef(FINGERPRINT, LEDGER_PATH)),
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

    /** An algo-sign-request on account 0 of the path 44'/283'/0'/0/0, with the codec's fields for [signData] and [signType]. */
    private fun requestCbor(signData: ByteArray, signType: Long): ByteArray {
        val components = LEDGER_PATH.flatMap { listOf(Cbor.Uint(it.index.toLong()), Cbor.Bool(it.hardened)) }
        val keypath = Cbor.Tagged(304, Cbor.Dict(listOf(Cbor.Uint(1L) to Cbor.Arr(components), Cbor.Uint(2L) to Cbor.Uint(FINGERPRINT.toLong()))))
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

    private companion object {
        val TESTNET: ChainId = algorandManifest.networks.first { it.testnet }.chainId
        val MAINNET: ChainId = algorandManifest.networks.first { !it.testnet }.chainId
        const val FINGERPRINT = 0x12121212
        val LEDGER_PATH = listOf(PathStep(44, true), PathStep(283, true), PathStep(0, true), PathStep(0, false), PathStep(0, false))
    }
}
