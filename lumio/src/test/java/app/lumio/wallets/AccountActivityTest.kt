package app.lumio.wallets

import app.airvault.core.model.AccountId
import app.airvault.core.model.ChainId
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.networkId
import app.lumio.data.net.Networks
import app.lumio.evm.EvmChain
import app.lumio.pair.PairFixtures
import app.lumio.transactions.HistoryRead
import app.lumio.transactions.evmNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reads of an account's activity: an Ethereum address on each network given, a Bitcoin account's scan, and nothing for Solana and Algorand. */
class AccountActivityTest {
    private val ethereum = EvmChain(1L, "Ethereum", "ETH", 18, false, null)
    private val base = EvmChain(8453L, "Base", "ETH", 18, false, null)
    private val address = "0x" + "ab".repeat(20)
    private val bitcoin = BtcAccountRef("w1", PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT))

    private fun account(chain: ChainId) = WalletAccount(
        walletId = "w1",
        id = AccountId.of(chain, address),
        index = 0,
        networkName = "network",
        coin = null,
        balance = null,
    )

    @Test
    fun anEthereumAccountReadsItsAddressOnEachNetworkGiven() {
        val plan = AccountActivity.plan(account(Networks.evm(1L)), listOf(ethereum, base), bitcoin = null)

        assertEquals(listOf(HistoryRead(evmNetwork(ethereum), address), HistoryRead(evmNetwork(base), address)), plan.evm)
        assertEquals(setOf(evmNetwork(ethereum), evmNetwork(base)), plan.networks)
        assertTrue(plan.btc.isEmpty())
    }

    @Test
    fun aBitcoinAccountReadsItsScanOnItsNetwork() {
        val plan = AccountActivity.plan(account(Networks.BTC_MAINNET), listOf(ethereum), bitcoin)

        assertEquals(listOf(bitcoin), plan.btc)
        assertEquals(setOf(bitcoin.account.network.networkId()), plan.networks)
        assertTrue(plan.evm.isEmpty())
    }

    @Test
    fun aBitcoinAccountOffTheSideReadsNothing() {
        val plan = AccountActivity.plan(account(Networks.BTC_MAINNET), listOf(ethereum), bitcoin = null)

        assertTrue(plan.networks.isEmpty())
        assertTrue(plan.btc.isEmpty())
    }

    @Test
    fun solanaAndAlgorandAccountsReadNothing() {
        for (chain in listOf(Networks.SOL_MAINNET, Networks.ALGO_MAINNET)) {
            val plan = AccountActivity.plan(account(chain), listOf(ethereum), bitcoin)

            assertTrue(plan.networks.isEmpty())
            assertTrue(plan.evm.isEmpty())
            assertTrue(plan.btc.isEmpty())
        }
    }
}
