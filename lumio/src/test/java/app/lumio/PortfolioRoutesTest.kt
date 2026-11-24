package app.lumio

import app.lumio.evm.EvmChain
import app.lumio.network.NetworkMode
import app.lumio.portfolio.PortfolioFormat
import app.lumio.portfolio.PortfolioHolding
import app.lumio.transactions.evmChainsByNetwork
import app.lumio.transactions.evmNetwork
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** The other side's card of an empty Portfolio: it names its chain as the feed names it, and it reads its amount as the holdings do. */
class PortfolioRoutesTest {
    private val monad = EvmChain(10143L, "Monad Testnet", "MON", 18, true, null)
    private val ethereum = EvmChain(1L, "Ethereum", "ETH", 18, false, null)
    private val chains = evmChainsByNetwork(listOf(monad, ethereum))

    private fun holding(chain: EvmChain, coin: String, amount: String) = PortfolioHolding(
        coin = coin,
        title = coin,
        network = evmNetwork(chain),
        amount = BigDecimal(amount),
        usd = null,
        change24h = null,
        mergeKey = null,
        bridged = false,
    )

    @Test
    fun theCardNamesItsChainByTheNameTheFeedShowsNotByItsChainId() {
        val card = otherSideOf(holding(monad, "MON", "12.5"), chains, NetworkMode.TESTNET) {}

        assertEquals("Monad Testnet", card.network)
        assertTrue(card.testnet)
    }

    @Test
    fun theCardReadsItsAmountAsTheHoldingsDo() {
        val card = otherSideOf(holding(monad, "MON", "12.5"), chains, NetworkMode.TESTNET) {}

        assertEquals("${PortfolioFormat.coins(BigDecimal("12.5"))} MON", card.amount)
    }

    @Test
    fun aMainnetCardIsNotTestnetAndSwitchesToTheSideItNames() {
        var switched = false
        val card = otherSideOf(holding(ethereum, "ETH", "1"), chains, NetworkMode.MAINNET) { switched = true }

        assertEquals("Ethereum", card.network)
        assertFalse(card.testnet)
        card.onSwitch()
        assertTrue(switched)
    }
}
