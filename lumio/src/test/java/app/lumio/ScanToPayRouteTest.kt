package app.lumio

import app.lumio.network.NetworkMode
import app.lumio.pair.PairedAccount
import app.lumio.pair.PairedWallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanToPayRouteTest {

    @Test
    fun scanToPayOpensTheSendOnTheNetworkItIsGivenWithTheCameraOn() {
        val route = scanToPayRoute(wallet(), BASE).orEmpty()

        assertTrue(route.startsWith("send/air1/"))
        assertTrue(route.endsWith("?chain=8453&scan=true"))
        assertTrue(scanToPayRoute(wallet(), MONAD_TESTNET).orEmpty().endsWith("?chain=10143&scan=true"))
    }

    @Test
    fun aWalletWithNoEthereumAccountHasNoScanToPaySend() {
        assertNull(scanToPayRoute(wallet(accounts = emptyList()), MONAD_TESTNET))
    }

    @Test
    fun scanToPayOpensNoSendWhileNoNetworkIsOn() {
        assertNull(scanToPayRoute(wallet(), null))
    }

    @Test
    fun scanToPayOpensOnThePrimaryNetworkWhenItIsOnAndOnAnotherNetworkWhenItIsNot() {
        assertEquals(MONAD_TESTNET, scanChainOf(listOf(BASE_SEPOLIA, MONAD_TESTNET), NetworkMode.TESTNET))
        assertEquals(BASE_SEPOLIA, scanChainOf(listOf(BASE_SEPOLIA), NetworkMode.TESTNET))
        assertEquals(BASE, scanChainOf(listOf(BASE), NetworkMode.MAINNET))
        assertNull(scanChainOf(emptyList(), NetworkMode.TESTNET))
    }

    @Test
    fun theNoteNamesWhatStopsScanToPayAndIsNullWhenNothingDoes() {
        assertEquals("Pair a wallet to scan to pay", scanNoteOf(wallet = null, chainId = BASE))
        assertEquals("This wallet has no Ethereum account to scan to pay", scanNoteOf(wallet(accounts = emptyList()), chainId = null))
        assertEquals("Turn on a network to scan to pay", scanNoteOf(wallet(), chainId = null))
        assertNull(scanNoteOf(wallet(), chainId = BASE))
    }

    private fun wallet(accounts: List<PairedAccount> = listOf(PairedAccount(index = 0, address = ADDRESS, name = null, label = null, group = null))) =
        PairedWallet(id = "air1", name = "Air 1", fingerprint = 1L, createdAtMs = 0L, bitcoin = emptyList(), evmAccounts = accounts)

    private companion object {
        const val BASE = 8453L
        const val BASE_SEPOLIA = 84532L
        const val MONAD_TESTNET = 10143L
        const val ADDRESS = "0x1020000000000000000000000000000000000122"
    }
}
