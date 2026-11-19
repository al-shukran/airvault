package app.lumio.wallets

import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.wallet.AccountAddresses
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcRow
import app.lumio.btc.BtcSnapshot
import app.lumio.btc.networkId
import app.lumio.chains.CachedNativeBalance
import app.lumio.chains.NativeCoin
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.evm.CachedBalance
import app.lumio.evm.EvmChain
import app.lumio.network.NetworkMode
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairedWallet
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accounts of a wallet with a Bitcoin, an Ethereum, a Solana and an Algorand key: one account per chain, each with its address, and each balance
 * read from the network of the side that is on. A side shows its own Bitcoin accounts and its own coins, so the other side's reads never show.
 */
class WalletAccountsTest {
    private val mainnetEthereum = EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = null)
    private val monad = EvmChain(10143L, "Monad testnet", "MON", 18, testnet = true, explorerUrl = null)
    private val solanaDevnet = NativeCoin(SOLANA_DEVNET, symbol = "SOL", name = "Solana Devnet", decimals = 9)
    private val algorandTestnet = NativeCoin(ALGORAND_TESTNET, symbol = "ALGO", name = "Algorand Testnet", decimals = 6)
    private val solanaKey = ByteArray(32) { 1 }
    private val algorandKey = ByteArray(32) { 2 }
    private val testnetBitcoin = PairFixtures.account(PairFixtures.seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
    private val wallet = PairedWallet(
        id = "wallet-a",
        name = "Demo",
        fingerprint = 1L,
        createdAtMs = 1L,
        bitcoin = listOf(testnetBitcoin),
        evmAccounts = PairFixtures.evmAccounts(),
        solana = listOf(solanaKey),
        algorand = listOf(algorandKey),
    )
    private val ethereumAddress = wallet.evmAccounts.first().address

    @Test
    fun aWalletWithEveryKindOfKeyYieldsOneAccountPerChainWithItsAddress() {
        val accounts = WalletAccounts.of(
            wallet,
            NetworkMode.TESTNET,
            evmChains = listOf(monad),
            evmCache = emptyList(),
            addressCount = 1,
            bitcoin = listOf(BtcRow(BtcAccountRef(wallet.id, testnetBitcoin), null)),
            coins = listOf(solanaDevnet, algorandTestnet),
            nativeCache = emptyList(),
        )

        assertEquals(listOf(AccountChain.BITCOIN, AccountChain.ETHEREUM, AccountChain.SOLANA, AccountChain.ALGORAND), accounts.map { it.chain })
        assertEquals(
            listOf(
                testnetBitcoin.receiveAddress(0),
                ethereumAddress,
                SolanaAccount.addressOf(solanaKey),
                AlgorandAccount.addressOf(algorandKey),
            ),
            accounts.map { it.address },
        )
        assertEquals(listOf(0, 0, 0, 0), accounts.map { it.index })
        assertTrue(accounts.all { it.walletId == wallet.id })
    }

    @Test
    fun theTestnetSideReadsEachBalanceFromItsOwnTestnetNetwork() {
        val accounts = WalletAccounts.of(
            wallet,
            NetworkMode.TESTNET,
            evmChains = listOf(mainnetEthereum, monad),
            evmCache = listOf(evmRead(1L, "5000000000000000000"), evmRead(10143L, "3000000000000000000")),
            addressCount = 1,
            bitcoin = listOf(BtcRow(BtcAccountRef(wallet.id, testnetBitcoin), snapshot(testnetBitcoin, confirmed = 120_000L))),
            coins = listOf(solanaDevnet, algorandTestnet),
            nativeCache = listOf(nativeRead(SOLANA_DEVNET, SolanaAccount.addressOf(solanaKey), "2500000000"), nativeRead(ALGORAND_TESTNET, AlgorandAccount.addressOf(algorandKey), "1000000")),
        )

        val (bitcoin, ethereum, solana, algorand) = accounts
        assertEquals(testnetBitcoin.receiveAddress(0), bitcoin.address)
        assertEquals(BigDecimal("0.00120000"), bitcoin.balance!!.balance.setScale(8))
        assertEquals(testnetBitcoin.network.networkId(), bitcoin.balance!!.network)
        assertEquals("Monad testnet", ethereum.networkName)
        assertEquals(0, BigDecimal("3").compareTo(ethereum.balance!!.balance))
        assertEquals(NetworkId(Networks.evm(10143), testnet = true), ethereum.balance!!.network)
        assertEquals(0, BigDecimal("2.5").compareTo(solana.balance!!.balance))
        assertEquals("Solana Devnet", solana.networkName)
        assertEquals(0, BigDecimal("1").compareTo(algorand.balance!!.balance))
        assertEquals("Algorand Testnet", algorand.networkName)
    }

    @Test
    fun theMainnetSideNeverShowsTheTestnetBitcoinOrTheTestnetEthereumBalance() {
        val accounts = WalletAccounts.of(
            wallet,
            NetworkMode.MAINNET,
            evmChains = listOf(mainnetEthereum, monad),
            evmCache = listOf(evmRead(1L, "5000000000000000000"), evmRead(10143L, "3000000000000000000")),
            addressCount = 1,
            bitcoin = listOf(BtcRow(BtcAccountRef(wallet.id, testnetBitcoin), snapshot(testnetBitcoin, confirmed = 120_000L))),
            coins = emptyList(),
            nativeCache = emptyList(),
        )

        // The testnet Bitcoin account is not on the mainnet side, so it is not listed; the Ethereum account reads its mainnet balance.
        assertEquals(listOf(AccountChain.ETHEREUM, AccountChain.SOLANA, AccountChain.ALGORAND), accounts.map { it.chain })
        val ethereum = accounts.first()
        assertEquals("Ethereum", ethereum.networkName)
        assertEquals(0, BigDecimal("5").compareTo(ethereum.balance!!.balance))
        // With no mainnet coin for Solana or Algorand, their accounts are listed with no balance and no testnet fallback.
        assertNull(accounts[1].balance)
        assertNull(accounts[2].balance)
    }

    private fun evmRead(chainId: Long, wei: String) = CachedBalance(
        walletId = wallet.id,
        chainId = chainId,
        address = ethereumAddress,
        wei = wei,
        nonce = 0L,
        atMs = 1L,
    )

    private fun nativeRead(network: NetworkId, address: String, amount: String) = CachedNativeBalance(
        walletId = wallet.id,
        network = network,
        address = address,
        amount = amount,
        atMs = 1L,
    )

    private fun snapshot(account: app.lensly.core.btc.BtcWatchOnlyAccount, confirmed: Long) = BtcSnapshot(
        key = BtcAccountRef(wallet.id, account).key,
        identity = "identity",
        scannedAtMs = 1L,
        tipHeight = 1L,
        confirmedSats = confirmed,
        unconfirmedSats = 0L,
        nextReceiveIndex = 1,
        nextReceiveAddress = "tb1qexample",
        utxos = emptyList(),
        transactions = emptyList(),
    )
}
