package app.lumio.pair

import app.lensly.app.export.PairingExport
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lensly's exports, encoded by the producers in :shared, read by Lumio's scan and importer. Each case compares what was imported. */
class PairImportRoundTripTest {

    @Test
    fun theEthereumAccountKeyRoundTrips() {
        val wallet = HdWallet(PairFixtures.seed)
        val pairing = pairingOf(PairingExport.urParts(wallet, name = "Demo")) as Pairing.Evm

        assertEquals(wallet.masterFingerprint.toLong() and 0xFFFFFFFFL, pairing.fingerprint)
        assertEquals("Demo", pairing.name)
        assertEquals(wallet.accountXpub().publicKey.toHex(), pairing.xpub.publicKey.toHex())
        assertEquals(wallet.accountXpub().chainCode.toHex(), pairing.xpub.chainCode.toHex())
        assertEquals(wallet.accountXpub().parentFingerprint, pairing.xpub.parentFingerprint)
        assertEquals(wallet.address(0), AccountAddresses.addressAt(pairing.xpub, 0))
    }

    @Test
    fun eachScriptTypeOnEachNetworkRoundTrips() {
        for (network in BtcNetwork.entries) {
            for (scriptType in ScriptType.entries) {
                val label = "$network $scriptType"
                val source = PairFixtures.account(PairFixtures.seed, network, scriptType)
                val accounts = (pairingOf(BtcWatchOnly.cryptoAccountParts(listOf(source))) as Pairing.Bitcoin).accounts
                val imported = accounts.single()

                assertEquals(label, source.network, imported.network)
                assertEquals(label, source.scriptType, imported.scriptType)
                assertEquals(label, source.masterFingerprint, imported.masterFingerprint)
                assertEquals(label, source.path.path, imported.path.path)
                assertEquals(label, source.xpub.publickeybytes, imported.xpub.publickeybytes)
                assertEquals(label, source.xpub.chaincode, imported.xpub.chaincode)
                assertEquals(label, source.descriptor, imported.descriptor)
                assertEquals(label, BtcAccount.fromSeed(PairFixtures.seed, network, scriptType).receiveAddress(0), imported.receiveAddress(0))
            }
        }
    }

    @Test
    fun theBitcoinExportWithBothNetworksRoundTripsAsAnAnimatedCode() {
        val parts = BtcWatchOnly.cryptoAccountParts(PairFixtures.allAccounts(PairFixtures.seed))
        assertTrue("an export of eight accounts is animated", parts.size > 1)

        val pairing = pairingOf(parts) as Pairing.Bitcoin

        assertEquals(PairFixtures.allAccounts(PairFixtures.seed).map { it.descriptor }, pairing.accounts.map { it.descriptor })
        assertEquals(setOf(BtcNetwork.MAINNET, BtcNetwork.TESTNET4), pairing.accounts.map { it.network }.toSet())
        assertEquals(ScriptType.entries.toSet(), pairing.accounts.map { it.scriptType }.toSet())
    }

    @Test
    fun theScanReportsProgressUntilTheAnimatedCodeIsWhole() {
        val parts = BtcWatchOnly.cryptoAccountParts(PairFixtures.allAccounts(PairFixtures.seed))
        val scan = PairScan()
        val steps = parts.map { scan.onText(it) }

        val readings = steps.filterIsInstance<ScanStep.Reading>().map { it.fraction }
        assertTrue("the frames before the last one report progress", readings.isNotEmpty())
        assertTrue("progress does not go back", readings.zipWithNext().all { (before, after) -> after >= before })
        assertTrue("the last frame completes the code", steps.last() is ScanStep.Complete)
    }
}
