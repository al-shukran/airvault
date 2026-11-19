package app.airvault.chains.algorand.signer

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.algorandManifest
import app.airvault.core.signer.ParseOutcome
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PathStep
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AlgorandRequestParserTest {

    /** A message carries no genesis hash, so it names the Algorand mainnet. It keeps the signer and the request id it names. */
    @Test
    fun aMessageNamesTheMainnetAndItsSigner() {
        val payload = requestCbor(signData = byteArrayOf(1, 2, 3), signType = 2L)
        val request = assertIs<ParseOutcome.Parsed>(parse(payload)).request
        assertEquals(algorandManifest.id, request.header.app)
        assertEquals(MAINNET, request.header.chain)
        assertEquals(RequestKind.MESSAGE, request.header.kind)
        assertEquals(listOf(SignerRef(FINGERPRINT, PATH)), request.header.signers)
        assertContentEquals(ByteArray(16) { 0x11 }, request.header.requestId)
        assertContentEquals(payload, request.body)
    }

    /** A UR of another type is not an Algorand request. */
    @Test
    fun aUrOfAnotherTypeIsRefused() {
        val payload = requestCbor(signData = byteArrayOf(1), signType = 2L)
        assertIs<ParseOutcome.Refused>(AlgorandRequestParser.parse("eth-sign-request", payload, ParserLimits()))
    }

    /** The core may lower the Algorand limit, never raise it: a payload over the Algorand limit is refused whatever the core allows. */
    @Test
    fun aPayloadOverTheAlgorandLimitIsRefusedWhateverTheCoreAllows() {
        val payload = requestCbor(signData = ByteArray(AlgoSignRequest.MAX_PAYLOAD_BYTES), signType = 2L)
        val generous = ParserLimits(maxSignDataBytes = Int.MAX_VALUE, maxPayloadBytes = Int.MAX_VALUE)
        val refused = assertIs<ParseOutcome.Refused>(AlgorandRequestParser.parse(AlgoSignRequest.UR_TYPE, payload, generous))
        assertEquals("payload exceeds ${AlgoSignRequest.MAX_PAYLOAD_BYTES} bytes", refused.reason)
    }

    /** A lower limit of the core applies too. */
    @Test
    fun aPayloadOverTheCoreLimitIsRefused() {
        val payload = requestCbor(signData = byteArrayOf(1, 2, 3), signType = 2L)
        val tight = ParserLimits(maxPayloadBytes = payload.size - 1)
        val refused = assertIs<ParseOutcome.Refused>(AlgorandRequestParser.parse(AlgoSignRequest.UR_TYPE, payload, tight))
        assertEquals("payload exceeds ${payload.size - 1} bytes", refused.reason)
    }

    /** Sign data over its limit is refused even when the payload fits. */
    @Test
    fun signDataOverItsLimitIsRefused() {
        val payload = requestCbor(signData = ByteArray(AlgoSignRequest.MAX_SIGN_DATA_BYTES + 1), signType = 2L)
        val refused = assertIs<ParseOutcome.Refused>(parse(payload))
        assertEquals("signData exceeds ${AlgoSignRequest.MAX_SIGN_DATA_BYTES} bytes", refused.reason)
    }

    /** A payload that is not CBOR is refused, and the parser does not throw. */
    @Test
    fun aPayloadThatIsNotCborIsRefused() {
        assertIs<ParseOutcome.Refused>(parse(byteArrayOf(0x7f)))
    }

    private fun parse(payload: ByteArray): ParseOutcome = AlgorandRequestParser.parse(AlgoSignRequest.UR_TYPE, payload, ParserLimits())

    /** An algo-sign-request on the path [PATH] of [FINGERPRINT], with the codec's fields for [signData] and [signType]. */
    private fun requestCbor(signData: ByteArray, signType: Long): ByteArray {
        val components = PATH.flatMap { listOf(Cbor.Uint(it.index.toLong()), Cbor.Bool(it.hardened)) }
        val keypath = Cbor.Tagged(
            304,
            Cbor.Dict(listOf(Cbor.Uint(1L) to Cbor.Arr(components), Cbor.Uint(2L) to Cbor.Uint(FINGERPRINT.toLong()))),
        )
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
        val MAINNET = algorandManifest.networks.first { !it.testnet }.chainId
        const val FINGERPRINT = 0x12121212
        val PATH = listOf(PathStep(44, true), PathStep(283, true), PathStep(0, true), PathStep(0, false), PathStep(0, false))
    }
}
