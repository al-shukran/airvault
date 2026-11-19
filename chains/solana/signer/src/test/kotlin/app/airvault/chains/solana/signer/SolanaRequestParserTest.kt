package app.airvault.chains.solana.signer

import app.airvault.chains.solana.SOLANA_DEVNET
import app.airvault.chains.solana.SOLANA_MAINNET
import app.airvault.core.model.ChainRegistry
import app.airvault.core.signer.ParseOutcome
import app.airvault.core.signer.RequestKind
import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PathStep
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SolanaRequestParserTest {

    @Test
    fun theManifestPassesTheRegistryAndRoutesTheRequestType() {
        assertEquals(SolanaSigner, ChainRegistry(listOf(SolanaSigner)).byRequestType(SolSignRequest.UR_TYPE))
    }

    @Test
    fun aTransactionNamingDevnetIsForDevnetAndKeepsItsPayloadAsTheBody() {
        val payload = requestCbor(signData = legacyMessage(), signType = 1L, cluster = "devnet")
        val parsed = assertIs<ParseOutcome.Parsed>(parse(payload)).request
        assertEquals(SOLANA_DEVNET, parsed.header.chain)
        assertEquals(RequestKind.TRANSACTION, parsed.header.kind)
        assertEquals(PathStep(44, hardened = true), parsed.header.signers.single().path.first())
        assertEquals(0x12121212, parsed.header.signers.single().fingerprint)
        assertContentEquals(payload, parsed.body)
    }

    @Test
    fun aRequestNamingNoClusterIsForMainnetBeta() {
        val parsed = assertIs<ParseOutcome.Parsed>(parse(requestCbor(signData = legacyMessage(), signType = 1L, cluster = null))).request
        assertEquals(SOLANA_MAINNET, parsed.header.chain)
    }

    @Test
    fun aMessageIsForMainnetBetaAndHasTheMessageKind() {
        val parsed = assertIs<ParseOutcome.Parsed>(parse(requestCbor(signData = offchainEnvelope(), signType = 2L, cluster = null))).request
        assertEquals(RequestKind.MESSAGE, parsed.header.kind)
        assertEquals(SOLANA_MAINNET, parsed.header.chain)
    }

    @Test
    fun aTransactionThatDoesNotParseIsRefused() {
        assertIs<ParseOutcome.Refused>(parse(requestCbor(signData = byteArrayOf(0x7f, 1, 2), signType = 1L, cluster = null)))
    }

    @Test
    fun aUrOfAnotherTypeIsRefused() {
        assertIs<ParseOutcome.Refused>(SolanaRequestParser.parse("algo-sign-request", requestCbor(legacyMessage(), 1L, null), ParserLimits()))
    }

    private fun parse(payload: ByteArray): ParseOutcome = SolanaRequestParser.parse(SolSignRequest.UR_TYPE, payload, ParserLimits())

    /** A legacy message with one signer, no instructions and a zero blockhash: the smallest message the parser accepts. */
    private fun legacyMessage(): ByteArray = byteArrayOf(1, 0, 0, 1) + ByteArray(32) { 7 } + ByteArray(32) + byteArrayOf(0)

    /** A version 0 off-chain envelope for the text "hello", signed by one key, in the restricted ASCII format. */
    private fun offchainEnvelope(): ByteArray {
        val domain = byteArrayOf(0xff.toByte()) + "solana offchain".toByteArray()
        val text = "hello".toByteArray()
        return domain + byteArrayOf(0) + ByteArray(32) + byteArrayOf(0, 1) + ByteArray(32) { 9 } + byteArrayOf(text.size.toByte(), 0) + text
    }

    /** A sol-sign-request on account 0 of the path 44'/501'/0', with the codec's fields for [signData], [signType] and [cluster]. */
    private fun requestCbor(signData: ByteArray, signType: Long, cluster: String?): ByteArray {
        val components = listOf(44L, 501L, 0L).flatMap { listOf(Cbor.Uint(it), Cbor.Bool(true)) }
        val keypath = Cbor.Tagged(304, Cbor.Dict(listOf(Cbor.Uint(1L) to Cbor.Arr(components), Cbor.Uint(2L) to Cbor.Uint(0x12121212L))))
        val fields = mutableListOf(
            Cbor.Uint(1L) to Cbor.Tagged(37, Cbor.Bytes(ByteArray(16) { 0x11 })),
            Cbor.Uint(2L) to Cbor.Bytes(signData),
            Cbor.Uint(3L) to keypath,
            Cbor.Uint(5L) to Cbor.Text("solflare"),
            Cbor.Uint(6L) to Cbor.Uint(signType),
        )
        if (cluster != null) fields += Cbor.Uint(7L) to Cbor.Text(cluster)
        return Cbor.encode(Cbor.Dict(fields))
    }
}
