package app.lumio.send

import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.btc.ScriptType
import app.lensly.core.solana.SolanaAccount
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairedWallet
import kotlin.test.assertEquals
import org.junit.Test

/** The owner's own accounts offered under "Your Accounts": each account of the wallet on the chain, except the one the send comes from. */
class OwnAccountChoicesTest {
    private val seed = PairFixtures.seed

    private fun wallet(
        bitcoin: List<BtcWatchOnlyAccount> = emptyList(),
        solana: List<ByteArray> = emptyList(),
        algorand: List<ByteArray> = emptyList(),
    ) = PairedWallet(
        id = "wallet",
        name = "Demo",
        fingerprint = PairFixtures.fingerprint(seed).toLong(),
        createdAtMs = 1L,
        bitcoin = bitcoin,
        solana = solana,
        algorand = algorand,
    )

    @Test
    fun theOtherBitcoinAccountsOnTheSendNetworkAreOffered() {
        val testnetNative = PairFixtures.account(seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
        val testnetTaproot = PairFixtures.account(seed, BtcNetwork.TESTNET4, ScriptType.TAPROOT)
        val mainnet = PairFixtures.account(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)
        val choices = bitcoinOwnChoices(
            wallet(bitcoin = listOf(testnetNative, testnetTaproot, mainnet)),
            BtcNetwork.TESTNET4,
            from = testnetNative.receiveAddress(0),
        )

        assertEquals(listOf("Account 2"), choices.map { it.title })
        assertEquals(listOf(testnetTaproot.receiveAddress(0)), choices.map { it.address })
    }

    @Test
    fun theOtherSolanaAccountsAreOffered() {
        val keys = (0..2).map { SolanaAccount.publicKey(seed, it) }
        val choices = solanaOwnChoices(wallet(solana = keys), from = SolanaAccount.address(seed, 0))

        assertEquals(listOf("Account 2", "Account 3"), choices.map { it.title })
        assertEquals(listOf(SolanaAccount.address(seed, 1), SolanaAccount.address(seed, 2)), choices.map { it.address })
    }

    @Test
    fun theOtherAlgorandAccountsAreOffered() {
        val keys = (0..2).map { AlgorandAccount.publicKey(seed, it) }
        val choices = algorandOwnChoices(wallet(algorand = keys), from = AlgorandAccount.addressOf(keys[1]))

        assertEquals(listOf("Account 1", "Account 3"), choices.map { it.title })
        assertEquals(listOf(AlgorandAccount.addressOf(keys[0]), AlgorandAccount.addressOf(keys[2])), choices.map { it.address })
    }
}
