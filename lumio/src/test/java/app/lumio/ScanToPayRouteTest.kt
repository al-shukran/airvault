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
    fun scanToPayOpensTheSendOnTheTestnetPrimaryNetworkWithTheCameraOn() {
        val route = scanToPayRoute(wallet(), NetworkMode.TESTNET, primaryOn = true).orEmpty()

        assertTrue(route.startsWith("send/air1/"))
        assertTrue(route.endsWith("?chain=10143&scan=true"))
    }

    @Test
    fun aWalletWithNoEthereumAccountHasNoScanToPaySend() {
        assertNull(scanToPayRoute(wallet(accounts = emptyList()), NetworkMode.TESTNET, primaryOn = true))
    }

    @Test
    fun scanToPayOpensNoSendWhileThePrimaryNetworkIsSwitchedOff() {
        assertNull(scanToPayRoute(wallet(), NetworkMode.TESTNET, primaryOn = false))
    }

    @Test
    fun theNoteNamesWhatStopsScanToPayAndIsNullWhenNothingDoes() {
        assertEquals("Pair a wallet to scan to pay", scanNoteOf(wallet = null, primaryOff = null))
        assertEquals("This wallet has no Ethereum account to scan to pay", scanNoteOf(wallet(accounts = emptyList()), primaryOff = "Monad Testnet"))
        assertEquals("Turn on Monad Testnet to scan to pay", scanNoteOf(wallet(), primaryOff = "Monad Testnet"))
        assertNull(scanNoteOf(wallet(), primaryOff = null))
    }

    private fun wallet(accounts: List<PairedAccount> = listOf(PairedAccount(index = 0, address = ADDRESS, name = null, label = null, group = null))) =
        PairedWallet(id = "air1", name = "Air 1", fingerprint = 1L, createdAtMs = 0L, bitcoin = emptyList(), evmAccounts = accounts)

    private companion object {
        const val ADDRESS = "0x1020000000000000000000000000000000000122"
    }
}
