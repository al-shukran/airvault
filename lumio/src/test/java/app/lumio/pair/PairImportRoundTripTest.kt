package app.lumio.pair

import app.lensly.app.autoAccountName
import app.lensly.app.export.PairingCodec
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.wallet.HdWallet
import app.lumio.data.net.Networks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lensly's pairing, encoded by the producer in :shared and read by Lumio's scan and importer. Each case compares what was imported. */
class PairImportRoundTripTest {

    /** The Auto-Name base of the wallet. An account named by its Auto-Name is sent with no name, and Lumio reads it back as the same name. */
    private val base = "Demo"

    @Test
    fun theEthereumAccountsWithTheirNamesLabelsAndGroupsRoundTrip() {
        val sent = PairFixtures.payloadOf(
            listOf(
                PairFixtures.ethereumAccount(0, name = "Savings", label = "cold", group = "Long term"),
                PairFixtures.ethereumAccount(1, name = "Trading", label = null, group = "Hot"),
            ),
        )

        val read = pairingOf(PairingCodec.urParts(sent, brotli = null))

        assertEquals(sent.fingerprint, read.fingerprint)
        assertEquals("Demo", read.name)
        assertEquals(sent.accounts.map { it.shape() }, read.accounts.map { it.shape() })
        val addresses = PairImport.ethereumAddressesOf(read)
        assertEquals(PairFixtures.address(PairFixtures.seed, 0), addresses[0])
        assertEquals(HdWallet(PairFixtures.seed).address(1), addresses[1])
    }

    @Test
    fun theAccountsZeroToTwoShowTheAddressesThatLenslyShows() {
        val sent = PairFixtures.payloadOf((0..2).map { PairFixtures.ethereumAccount(it, "Demo $it") })

        val addresses = PairImport.ethereumAddressesOf(pairingOf(PairingCodec.urParts(sent, brotli = null)))

        assertEquals(3, addresses.size)
        for (account in 0..2) {
            assertEquals("account $account", HdWallet(PairFixtures.seed).address(account), addresses[account])
        }
    }

    @Test
    fun eachScriptTypeOnEachNetworkRoundTrips() {
        val accounts = PairFixtures.allAccounts(PairFixtures.seed)
        val sent = PairFixtures.payloadOf(
            listOf(PairFixtures.ethereumAccount(0, "Demo 0")) + PairFixtures.bitcoinAccounts(base = base),
        )

        val imported = PairImport.bitcoinAccountsOf(pairingOf(PairingCodec.urParts(sent, brotli = null)))

        assertEquals(accounts.map { it.shape() }, imported.map { it.shape() })
        assertEquals(accounts.map { it.receiveAddress(0) }, imported.map { it.receiveAddress(0) })
        assertEquals(accounts.map { it.changeAddress(0) }, imported.map { it.changeAddress(0) })
        assertEquals(setOf(BtcNetwork.MAINNET, BtcNetwork.TESTNET4), imported.map { it.network }.toSet())
    }

    @Test
    fun theBitcoinAccountsWithTheirNamesLabelsAndGroupsRoundTrip() {
        val bitcoinAccounts = PairFixtures.allAccounts(PairFixtures.seed)
        val named = bitcoinAccounts.mapIndexed { index, account ->
            when (index) {
                2 -> PairFixtures.bitcoinAccount(account, "Cold", label = "vault", group = "Long term")
                0 -> PairFixtures.bitcoinAccount(account, "Savings 0")
                else -> PairFixtures.bitcoinAccount(account, autoAccountName(base, index))
            }
        }
        val sent = PairFixtures.payloadOf(listOf(PairFixtures.ethereumAccount(0, "Demo 0")) + named, base = base)

        val read = pairingOf(PairingCodec.urParts(sent, brotli = null))

        assertEquals(named.map { it.shape() }, PairImport.accountsOf(read, Networks.BIP122).map { it.shape() })
        assertEquals(bitcoinAccounts.map { it.shape() }, PairImport.bitcoinAccountsOf(read).map { it.shape() })
    }

    @Test
    fun aPairingWithOnlyBitcoinIsImported() {
        val sent = PairFixtures.payloadOf(PairFixtures.bitcoinAccounts(base = base), base = base)

        val read = pairingOf(PairingCodec.urParts(sent, brotli = null))

        assertEquals(sent.accounts.map { it.shape() }, read.accounts.map { it.shape() })
        assertEquals(listOf(Networks.BIP122), read.accounts.map { it.export.chain.namespace }.distinct())
        assertTrue(PairImport.ethereumAddressesOf(read).isEmpty())
        assertEquals(PairFixtures.allAccounts(PairFixtures.seed).map { it.shape() }, PairImport.bitcoinAccountsOf(read).map { it.shape() })
    }

    @Test
    fun theWholePairingOfEveryAppRoundTripsAsAnAnimatedCode() {
        val bitcoinAccounts = PairFixtures.allAccounts(PairFixtures.seed)
        val ethereum = (0 until 40).map { index ->
            PairFixtures.ethereumAccount(index, "Account $index", label = "label $index", group = if (index % 2 == 0) "Even" else null)
        }
        val parts = PairingCodec.urParts(PairFixtures.payloadOf(ethereum + PairFixtures.bitcoinAccounts(base = base), base = base), brotli = null)
        assertTrue("a long pairing is animated", parts.size > 1)

        val read = pairingOf(parts)

        assertEquals(listOf("eip155", "bip122"), read.accounts.map { it.export.chain.namespace }.distinct())
        assertEquals(ethereum.map { it.shape() }, PairImport.accountsOf(read, Networks.EIP155).map { it.shape() })
        assertEquals(bitcoinAccounts.map { it.shape() }, PairImport.bitcoinAccountsOf(read).map { it.shape() })
    }

    @Test
    fun theScanReportsProgressUntilTheAnimatedCodeIsWhole() {
        val accounts = listOf(PairFixtures.ethereumAccount(0, "Demo 0"))
        val parts = PairingCodec.urParts(PairFixtures.payloadOf(accounts + PairFixtures.bitcoinAccounts(base = base), base = base), brotli = null)
        val scan = PairScan()
        val steps = parts.map { scan.onText(it) }

        val readings = steps.filterIsInstance<ScanStep.Reading>().map { it.fraction }
        assertTrue("the frames before the last one report progress", readings.isNotEmpty())
        assertTrue("progress does not go back", readings.zipWithNext().all { (before, after) -> after >= before })
        assertTrue("the last frame completes the code", steps.last() is ScanStep.Complete)
    }
}
