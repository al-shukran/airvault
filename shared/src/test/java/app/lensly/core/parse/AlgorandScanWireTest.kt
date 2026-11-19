package app.lensly.core.parse

import app.airvault.chains.algorand.algorandManifest
import app.airvault.chains.algorand.signer.AlgoTxnDecoder
import app.airvault.core.model.ChainId
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.core.algorand.AlgoTxnVectors
import app.lensly.core.algorand.AlgorandRequestCheck.UNKNOWN_NETWORK
import app.lensly.core.ur.framesOf
import com.sparrowwallet.hummingbird.UR
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
        val payload = algorandRequestCbor(signData = pay("pay_basic"), signType = 1L)
        val call = ParserWire.encodeScanRequest(session = 1L, base = 0, parts = parts(payload))
        val reply = ParserWire.decodeScanReply(SandboxAnswers().answer(ParserWire.SCAN, call))
        assertSameRequest(AppRequest(headerOf(TESTNET, RequestKind.TRANSACTION), payload), assertIs<ScanReply.App>(reply).request)
    }

    /** A transaction names its chain by the genesis hash it carries, so a transaction on mainnet is answered as mainnet. */
    @Test
    fun aTransactionNamesTheChainOfItsGenesisHash() {
        val payload = algorandRequestCbor(signData = pay("axfer_mainnet_usdc"), signType = 1L)
        assertEquals(MAINNET, assertIs<ScanReply.App>(InProcessParser.scan(parts(payload))).request.header.chain)
    }

    /** A message carries no genesis hash, and the request carries no network, so a message names the Algorand mainnet. */
    @Test
    fun aMessageToSignNamesTheAlgorandMainnet() {
        val payload = algorandRequestCbor(signData = byteArrayOf(0x7f, 1, 2), signType = 2L)
        val request = assertIs<ScanReply.App>(InProcessParser.scan(parts(payload))).request
        assertSameRequest(AppRequest(headerOf(MAINNET, RequestKind.MESSAGE), payload), request)
    }

    /** A transaction the decoder refuses is refused by the sandbox, and never reaches the app as a request. */
    @Test
    fun anAlgorandTransactionThatDoesNotDecodeIsRefused() {
        val payload = algorandRequestCbor(signData = byteArrayOf(0x7f, 1, 2), signType = 1L)
        assertIs<ScanReply.Refused>(InProcessParser.scan(parts(payload)))
    }

    /**
     * A genesis hash of a network Lensly does not sign for still names a chain, so the sandbox answers under that chain's CAIP-2 id. The
     * review refuses it there, where the refusal is recorded under the wallet.
     */
    @Test
    fun aTransactionOnAnotherNetworkReachesTheReviewUnderItsOwnChain() {
        val payload = algorandRequestCbor(withGenesisHash(pay("pay_basic"), ByteArray(32) { 0x11 }), signType = 1L)
        val chain = assertIs<ScanReply.App>(InProcessParser.scan(parts(payload))).request.header.chain
        assertTrue(chain != TESTNET && chain != MAINNET, "the chain $chain is one of the networks Lensly signs for")
    }

    /** A genesis hash whose first 32 base64 characters are no CAIP-2 reference names no chain at all, so the scan refuses it. */
    @Test
    fun aTransactionWhoseGenesisHashNamesNoChainIsRefused() {
        val payload = algorandRequestCbor(withGenesisHash(pay("pay_basic"), ByteArray(32) { 0xff.toByte() }), signType = 1L)
        assertEquals(UNKNOWN_NETWORK, assertIs<ScanReply.Refused>(InProcessParser.scan(parts(payload))).reason)
    }

    /** One complete UR part. The payload is small, so it is a single frame. */
    private fun parts(payload: ByteArray): List<String> =
        framesOf(UR("algo-sign-request", payload), maxFragment = 4096).take(1).toList()

    private fun pay(name: String): ByteArray = AlgoTxnVectors.valid().single { it.name == name }.raw

    /** The header that [algorandRequestCbor] describes: the Algorand app, [chain] and [kind], the one signer it names, and its request id. */
    private fun headerOf(chain: ChainId, kind: RequestKind) = RequestHeader(
        app = algorandManifest.id,
        chain = chain,
        kind = kind,
        signers = listOf(SignerRef(ALGORAND_TEST_FINGERPRINT, ALGORAND_TEST_PATH)),
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

    /** The transaction [txn] with its genesis hash replaced by [hash]. The length is the same, so the decoder still reads it. */
    private fun withGenesisHash(txn: ByteArray, hash: ByteArray): ByteArray {
        val old = AlgoTxnDecoder.decode(txn).genesisHash
        val at = (0..txn.size - old.size).first { txn.copyOfRange(it, it + old.size).contentEquals(old) }
        return txn.copyOf().also { hash.copyInto(it, at) }
    }

    private companion object {
        val TESTNET: ChainId = algorandManifest.networks.first { it.testnet }.chainId
        val MAINNET: ChainId = algorandManifest.networks.first { !it.testnet }.chainId
    }
}
