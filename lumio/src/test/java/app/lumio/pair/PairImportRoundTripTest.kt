package app.lumio.pair

import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingKey
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lensly's pairing, encoded by the producer in :shared and read by Lumio's scan and importer. Each case compares what was imported. */
class PairImportRoundTripTest {

    private fun ethereum(accounts: List<PairingAccount>) = PairingApp(
        app = "ethereum",
        keys = accounts.indices.map { PairingKey.ethereumAddress(it, PairFixtures.addressKey(PairFixtures.seed, it)) },
        accounts = accounts.mapIndexed { index, account -> account.copy(key = index, child = null) },
    )

    private fun bitcoin(accounts: List<BtcWatchOnlyAccount>) = PairingApp(
        app = "bitcoin",
        keys = accounts.map { PairingKey.bitcoinAccount(it) },
        accounts = accounts.indices.map { PairingAccount(key = it, child = null, name = null, label = null, group = null) },
    )

    private fun pairing(vararg apps: PairingApp) = PairingPayload(
        fingerprint = HdWallet(PairFixtures.seed).masterFingerprint.toLong() and 0xFFFFFFFFL,
        name = "Demo",
        apps = apps.toList(),
    )

    @Test
    fun theEthereumAccountsWithTheirNamesLabelsAndGroupsRoundTrip() {
        val sent = pairing(
            ethereum(
                listOf(
                    PairingAccount(key = 0, child = null, name = "Savings", label = "cold", group = "Long term"),
                    PairingAccount(key = 0, child = null, name = "Trading", label = null, group = "Hot"),
                ),
            ),
        )

        val read = pairingOf(PairingCodec.urParts(sent, brotli = null))

        assertEquals(sent.fingerprint, read.fingerprint)
        assertEquals("Demo", read.name)
        assertEquals(sent.apps.single().accounts, read.apps.single { it.app == "ethereum" }.accounts)
        val xpub = PairImport.ethereumAccountKeysOf(read).first()
        assertEquals(PairFixtures.addressKey(PairFixtures.seed, 0).publicKey.toHex(), xpub.publicKey.toHex())
        assertEquals(HdWallet(PairFixtures.seed).address(1), AccountAddresses.addressOfKey(PairImport.ethereumAccountKeysOf(read)[1]))
    }

    @Test
    fun theAccountsZeroToTwoShowTheAddressesThatLenslyShows() {
        val sent = pairing(
            ethereum(List(3) { index -> PairingAccount(key = index, child = null, name = "Demo $index", label = null, group = null) }),
        )

        val keys = PairImport.ethereumAccountKeysOf(pairingOf(PairingCodec.urParts(sent, brotli = null)))

        assertEquals(3, keys.size)
        for (account in 0..2) {
            assertEquals("account $account", HdWallet(PairFixtures.seed).address(account), AccountAddresses.addressOfKey(keys[account]))
        }
    }

    @Test
    fun eachScriptTypeOnEachNetworkRoundTrips() {
        val accounts = PairFixtures.allAccounts(PairFixtures.seed)
        val sent = pairing(ethereum(listOf(PairingAccount(key = 0, child = null, name = null, label = null, group = null))), bitcoin(accounts))

        val imported = PairImport.bitcoinAccountsOf(pairingOf(PairingCodec.urParts(sent, brotli = null)))

        assertEquals(accounts.map { it.descriptor }, imported.map { it.descriptor })
        assertEquals(accounts.map { it.receiveAddress(0) }, imported.map { it.receiveAddress(0) })
        assertEquals(setOf(BtcNetwork.MAINNET, BtcNetwork.TESTNET4), imported.map { it.network }.toSet())
    }

    @Test
    fun theBitcoinAccountsWithTheirNamesLabelsAndGroupsRoundTrip() {
        val bitcoinAccounts = PairFixtures.allAccounts(PairFixtures.seed)
        val named = listOf(
            PairingAccount(key = 2, child = null, name = "Cold", label = "vault", group = "Long term"),
            PairingAccount(key = 0, child = null, name = "Savings 0", label = null, group = null),
        )
        val bitcoinApp = PairingApp(app = "bitcoin", keys = bitcoinAccounts.map { PairingKey.bitcoinAccount(it) }, accounts = named)
        val sent = pairing(ethereum(listOf(PairingAccount(key = 0, child = null, name = null, label = null, group = null))), bitcoinApp)

        val read = pairingOf(PairingCodec.urParts(sent, brotli = null))

        assertEquals(named, read.apps.single { it.app == "bitcoin" }.accounts)
        assertEquals(bitcoinAccounts.map { it.descriptor }, PairImport.bitcoinAccountsOf(read).map { it.descriptor })
    }

    @Test
    fun theWholePairingOfEveryAppRoundTripsAsAnAnimatedCode() {
        val bitcoinAccounts = PairFixtures.allAccounts(PairFixtures.seed)
        val accounts = (0 until 40).map { index ->
            PairingAccount(key = index, child = null, name = "Account $index", label = "label $index", group = if (index % 2 == 0) "Even" else null)
        }
        val parts = PairingCodec.urParts(pairing(ethereum(accounts), bitcoin(bitcoinAccounts)), brotli = null)
        assertTrue("a long pairing is animated", parts.size > 1)

        val read = pairingOf(parts)

        assertEquals(listOf("ethereum", "bitcoin"), read.apps.map { it.app })
        assertEquals(accounts, read.apps.single { it.app == "ethereum" }.accounts)
        assertEquals(bitcoinAccounts.map { it.descriptor }, PairImport.bitcoinAccountsOf(read).map { it.descriptor })
    }

    @Test
    fun theScanReportsProgressUntilTheAnimatedCodeIsWhole() {
        val accounts = listOf(PairingAccount(key = 0, child = null, name = "Demo 0", label = null, group = null))
        val parts = PairingCodec.urParts(pairing(ethereum(accounts), bitcoin(PairFixtures.allAccounts(PairFixtures.seed))), brotli = null)
        val scan = PairScan()
        val steps = parts.map { scan.onText(it) }

        val readings = steps.filterIsInstance<ScanStep.Reading>().map { it.fraction }
        assertTrue("the frames before the last one report progress", readings.isNotEmpty())
        assertTrue("progress does not go back", readings.zipWithNext().all { (before, after) -> after >= before })
        assertTrue("the last frame completes the code", steps.last() is ScanStep.Complete)
    }
}
