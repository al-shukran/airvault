package app.lensly.core.frozen

import app.airvault.chains.algorand.signer.AlgorandRequestParser
import app.airvault.chains.bitcoin.signer.BitcoinRequestParser
import app.airvault.chains.evm.signer.EvmRequestParser
import app.airvault.chains.solana.signer.SolanaRequestParser
import app.airvault.core.signer.ParseOutcome
import app.airvault.core.signer.RequestParser
import app.lensly.core.parse.ParserLimits
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/** Every request a frozen Lensly is sent parses through the parser of its chain, so a parser change that would refuse one fails here. */
class FrozenLenslyRequestsTest {
    @Test
    fun everyFrozenEthereumRequestParses() {
        assertParses(EvmRequestParser, "request-eth-transaction", "request-eth-typed-data")
    }

    @Test
    fun everyFrozenSolanaRequestParses() {
        assertParses(SolanaRequestParser, "request-sol")
    }

    @Test
    fun everyFrozenAlgorandRequestParses() {
        assertParses(AlgorandRequestParser, "request-algo")
    }

    @Test
    fun everyFrozenBitcoinRequestParses() {
        assertParses(BitcoinRequestParser, "request-psbt", "request-crypto-psbt")
    }

    @Test
    fun aFrozenEthereumPersonalMessageParses() {
        assertParses(EvmRequestParser, "request-eth-personal-message")
    }

    @Test
    fun aFrozenSolanaOffChainMessageParses() {
        assertParses(SolanaRequestParser, "request-sol-off-chain-message")
    }

    /** A reserved key (1000 and above) is ignored, so the request parses exactly as the same request without it. */
    @Test
    fun aFrozenRequestWithAReservedKeyParsesAsIfTheKeyWereAbsent() {
        val withoutKey = parsedOf(EvmRequestParser, "request-eth-transaction")
        val withKey = parsedOf(EvmRequestParser, "request-eth-reserved-key")
        // The header holds the request id as a byte array, so the two ids are compared by content.
        assertEquals(withoutKey.request.header.copy(requestId = null), withKey.request.header.copy(requestId = null))
        assertTrue(withoutKey.request.header.requestId.contentEquals(withKey.request.header.requestId))
        assertTrue(withoutKey.request.body.contentEquals(withKey.request.body))
    }

    private fun assertParses(parser: RequestParser, vararg names: String) {
        for (name in names) parsedOf(parser, name)
    }

    private fun parsedOf(parser: RequestParser, name: String): ParseOutcome.Parsed {
        val ur = FrozenFixtures.ur(name)
        return assertIs<ParseOutcome.Parsed>(parser.parse(ur.type, ur.cborBytes, ParserLimits()), name)
    }
}
