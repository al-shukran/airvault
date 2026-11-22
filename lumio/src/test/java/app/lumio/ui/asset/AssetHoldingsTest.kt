package app.lumio.ui.asset

import app.airvault.core.model.AccountId
import app.airvault.core.model.ChainId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.home.NativeAsset
import app.lumio.wallets.WalletAccount
import java.math.BigDecimal
import kotlin.test.assertEquals
import org.junit.Test

/** Holdings Breakdown's grouping and the asset page's text: a coin by chain, the largest account first, and the amounts and addresses as written. */
class AssetHoldingsTest {
    private val ethereum = Networks.evm(1L)
    private val arbitrum = Networks.evm(42161L)

    private fun account(chain: ChainId, network: String, name: String?, held: NativeAsset?, index: Int = 0): WalletAccount = WalletAccount(
        walletId = "wallet",
        id = AccountId.of(chain, "0x102030405060708090a0b0c0d0e0f0101122b"),
        index = index,
        networkName = network,
        coin = null,
        balance = held,
        name = name,
    )

    private fun eth(chain: ChainId, amount: String, symbol: String = "ETH"): NativeAsset = NativeAsset(
        id = "$symbol-$amount",
        symbol = symbol,
        name = symbol,
        network = NetworkId(chain, testnet = false),
        balance = BigDecimal(amount),
    )

    @Test
    fun chainsAreInNameOrderAndEachChainsAccountsLargestFirst() {
        val accounts = listOf(
            account(ethereum, "Ethereum", "Unnamed", eth(ethereum, "1")),
            account(arbitrum, "Arbitrum", "FelixStone", eth(arbitrum, "3.23")),
            account(arbitrum, "Arbitrum", "PulsarNova", eth(arbitrum, "10"), index = 5),
        )

        val chains = chainHoldings(accounts, symbol = "ETH", walletName = "Air 1")

        assertEquals(listOf("Arbitrum", "Ethereum"), chains.map { it.chain })
        assertEquals(listOf(BigDecimal("13.23"), BigDecimal("1")), chains.map { it.total })
        assertEquals(listOf("PulsarNova", "FelixStone"), chains.first().accounts.map { it.name })
        assertEquals("Air 1 #5 · 0x1020…122b", chains.first().accounts.first().detail)
    }

    @Test
    fun otherCoinsAndEmptyBalancesAreLeftOut() {
        val accounts = listOf(
            account(ethereum, "Ethereum", "Holds", eth(ethereum, "2")),
            account(ethereum, "Ethereum", "Empty", eth(ethereum, "0")),
            account(ethereum, "Ethereum", "Usdc", eth(ethereum, "500", symbol = "USDC")),
            account(ethereum, "Ethereum", "NoRead", held = null),
        )

        val chains = chainHoldings(accounts, symbol = "ETH", walletName = "Air 1")

        assertEquals(listOf("Holds"), chains.single().accounts.map { it.name })
    }

    @Test
    fun anAddressKeepsItsFirstSixAndLastFourCharacters() {
        assertEquals("0x1020…122b", shortAddress("0x102030405060708090a0b0c0d0e0f0101122b"))
        assertEquals("0x1020", shortAddress("0x1020"))
    }

    @Test
    fun theSummaryCountsAccountsAndChainsInTheirSingularOrPlural() {
        assertEquals("5 accounts · 3 chains", holdingsSummary(accounts = 5, chains = 3))
        assertEquals("1 account · 1 chain", holdingsSummary(accounts = 1, chains = 1))
    }

    @Test
    fun anAmountDropsItsTrailingZerosAndKeepsItsTicker() {
        assertEquals("4 ETH", amountText(BigDecimal("4.000"), "ETH"))
        assertEquals("22.23 ETH", amountText(BigDecimal("22.23"), "ETH"))
    }
}
