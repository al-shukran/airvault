package app.lumio

import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.solana.SolanaAccount
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairedWallet
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** The owner of a transaction, and the address a counterparty is compared by, for each of the four chains. */
class TransactionOwnershipTest {
    private val evmAddress = PairFixtures.address(PairFixtures.seed, 0)
    private val solAddress = SolanaAccount.addressOf(SolanaAccount.publicKey(PairFixtures.seed, 0))
    private val btcAddress = PairFixtures.account(PairFixtures.otherSeed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT).receiveAddress(0)
    private val algoAddress = AlgorandAccount.addressOf(AlgorandAccount.publicKey(PairFixtures.otherSeed, 0))

    /** Alpha holds the Ethereum and Solana accounts, and Beta the Bitcoin and Algorand accounts, so the first wallet owns only two of the four. */
    private val alpha = PairedWallet(
        id = "alpha",
        name = "Alpha",
        fingerprint = PairFixtures.fingerprint(PairFixtures.seed).toLong(),
        createdAtMs = 1L,
        bitcoin = emptyList(),
        evmAccounts = PairFixtures.evmAccounts(1),
        solana = listOf(SolanaAccount.publicKey(PairFixtures.seed, 0)),
    )
    private val beta = PairedWallet(
        id = "beta",
        name = "Beta",
        fingerprint = PairFixtures.fingerprint(PairFixtures.otherSeed).toLong(),
        createdAtMs = 2L,
        bitcoin = listOf(PairFixtures.account(PairFixtures.otherSeed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)),
        algorand = listOf(AlgorandAccount.publicKey(PairFixtures.otherSeed, 0)),
    )

    @Test
    fun eachChainsTransactionMapsToItsOwnerWallet() {
        val wallets = listOf(alpha, beta)
        assertEquals("alpha", walletIdOf(wallets, evmAddress))
        assertEquals("beta", walletIdOf(wallets, btcAddress))
        assertEquals("alpha", walletIdOf(wallets, solAddress))
        assertEquals("beta", walletIdOf(wallets, algoAddress))
    }

    @Test
    fun anEthereumAddressMatchesInAnyCase() {
        assertTrue(isSameAddress(evmAddress.lowercase(), evmAddress))
        assertTrue(isSameAddress("0x" + evmAddress.drop(2).uppercase(), evmAddress))
        assertFalse(isSameAddress(PairFixtures.address(PairFixtures.seed, 1), evmAddress))
    }

    @Test
    fun aBitcoinAddressMatchesItsOwnAndNoOther() {
        val otherBtc = PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT).receiveAddress(0)
        assertTrue(isSameAddress(btcAddress, btcAddress))
        assertFalse(isSameAddress(otherBtc, btcAddress))
    }

    @Test
    fun aSolanaAddressMatchesExactly() {
        assertTrue(isSameAddress(solAddress, solAddress))
        assertFalse(isSameAddress(solAddress.lowercase(), solAddress))
        assertFalse(isSameAddress(SolanaAccount.addressOf(SolanaAccount.publicKey(PairFixtures.otherSeed, 0)), solAddress))
    }

    @Test
    fun anAlgorandAddressMatchesExactly() {
        assertTrue(isSameAddress(algoAddress, algoAddress))
        assertFalse(isSameAddress(algoAddress.lowercase(), algoAddress))
        assertFalse(isSameAddress(AlgorandAccount.addressOf(AlgorandAccount.publicKey(PairFixtures.seed, 0)), algoAddress))
    }

    @Test
    fun noCounterpartyOrAFreeTextNameIsNotTheAddress() {
        assertFalse(isSameAddress(null, evmAddress))
        assertFalse(isSameAddress("Exchange", evmAddress))
    }
}
