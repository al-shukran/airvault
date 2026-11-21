package app.lensly.core.frozen

import app.airvault.chains.algorand.signer.AlgorandRequestParser
import app.airvault.chains.bitcoin.signer.BitcoinRequestParser
import app.airvault.chains.evm.signer.EvmRequestParser
import app.airvault.chains.solana.signer.SolanaRequestParser
import app.airvault.core.signer.ParseOutcome
import app.airvault.core.signer.RequestParser
import app.lensly.core.parse.ParserLimits
import kotlin.test.assertIs
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

    private fun assertParses(parser: RequestParser, vararg names: String) {
        for (name in names) {
            val ur = FrozenFixtures.ur(name)
            assertIs<ParseOutcome.Parsed>(parser.parse(ur.type, ur.cborBytes, ParserLimits()), name)
        }
    }
}
