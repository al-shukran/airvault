package app.lensly.core.btc

import fr.acinq.bitcoin.psbt.Psbt
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BtcAccountSelectionTest {
    private val seed = BitcoinVectors.seed()
    private val mainnet = BtcNetwork.MAINNET
    private val testnet4 = BtcNetwork.TESTNET4

    private fun account(network: BtcNetwork, index: Int) = BtcAccount.fromSeed(seed, network, ScriptType.NATIVE_SEGWIT, index)

    /** A request that spends one output of each of [from], paying the first of them. */
    private fun spending(vararg from: BtcAccount): Psbt {
        val spends = from.mapIndexed { i, account ->
            BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L, salt = i + 1), account, 0, 0)
        }
        return BtcFixtures.psbt(spends, BtcFixtures.paying(from.first(), paid = 30_000L, change = 60_000L))
    }

    @Test
    fun theAccountTheInputsNameIsChosen() {
        val accounts = listOf(account(mainnet, 0), account(mainnet, 1))
        assertEquals(1, BtcAccountSelection.select(accounts, spending(accounts[1])).accountIndex)
    }

    @Test
    fun inputsOfTwoAccountsOfTheWalletAreRefused() {
        val accounts = listOf(account(mainnet, 0), account(mainnet, 1))
        val refused = assertFailsWith<BtcRefusedException> { BtcAccountSelection.select(accounts, spending(accounts[0], accounts[1])) }
        assertEquals(BtcRefusal.SeveralAccounts, refused.refusal)
    }

    @Test
    fun inputsOnANetworkTheWalletHoldsNoAccountOnAreANetworkMismatch() {
        val accounts = listOf(account(mainnet, 0))
        val refused = assertFailsWith<BtcRefusedException> { BtcAccountSelection.select(accounts, spending(account(testnet4, 0))) }
        assertEquals(BtcRefusal.NetworkMismatch, refused.refusal)
    }

    @Test
    fun inputsOfAnAccountTheWalletDoesNotHoldAreNoStoredAccount() {
        val accounts = listOf(account(mainnet, 0))
        val refused = assertFailsWith<BtcRefusedException> { BtcAccountSelection.select(accounts, spending(account(mainnet, 3))) }
        assertEquals(BtcRefusal.NoStoredAccount, refused.refusal)
    }

    @Test
    fun aWalletWithNoAccountsRefusesEveryRequest() {
        val refused = assertFailsWith<BtcRefusedException> { BtcAccountSelection.select(emptyList(), spending(account(mainnet, 0))) }
        assertEquals(BtcRefusal.NoStoredAccount, refused.refusal)
    }
}
