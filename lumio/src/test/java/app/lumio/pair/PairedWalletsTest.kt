package app.lumio.pair

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.ScriptType
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.vault.WalletRecordNames
import app.lensly.core.vault.MemoryKeystore
import app.lensly.app.export.PairingExport
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.toHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The paired wallets in an open vault: a new pairing, an update of the same fingerprint, a second family, a delete, and read-back. */
class PairedWalletsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()
    private val session by lazy {
        VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it * 7 + 3).toByte() })
    }

    @Test
    fun anEthereumPairingIsStoredAndListedWithItsName() {
        val pairing = pairingOf(PairingExport.urParts(HdWallet(PairFixtures.seed), name = "Demo")) as Pairing.Evm

        val saved = PairedWallets.save(session, pairing, nowMs = 100L)

        assertTrue(saved.created)
        val listed = PairedWallets.list(session).single()
        assertEquals(saved.id, listed.id)
        assertEquals("Demo", listed.name)
        assertEquals(pairing.fingerprint, listed.fingerprint)
        assertEquals(setOf(PairFamily.EVM), listed.families)
        assertEquals(pairing.xpub.publicKey.toHex(), listed.evm!!.publicKey.toHex())
    }

    @Test
    fun repairingTheSameFingerprintUpdatesTheWalletAndDoesNotDuplicateIt() {
        val wallet = HdWallet(PairFixtures.seed)
        val first = PairedWallets.save(session, pairingOf(PairingExport.urParts(wallet, name = "Demo")), nowMs = 100L)
        val second = PairedWallets.save(session, pairingOf(PairingExport.urParts(wallet, name = "Renamed")), nowMs = 200L)

        assertEquals(first.id, second.id)
        assertEquals(false, second.created)
        val listed = PairedWallets.list(session)
        assertEquals(1, listed.size)
        assertEquals("Renamed", listed.single().name)
        assertEquals(100L, listed.single().createdAtMs)
    }

    @Test
    fun bitcoinForTheSameSeedJoinsTheWalletAndKeepsTheEthereumKey() {
        val wallet = HdWallet(PairFixtures.seed)
        PairedWallets.save(session, pairingOf(PairingExport.urParts(wallet, name = "Demo")), nowMs = 100L)

        val bitcoin = pairingOf(BtcWatchOnly.cryptoAccountParts(PairFixtures.allAccounts(PairFixtures.seed)))
        val saved = PairedWallets.save(session, bitcoin, nowMs = 200L)

        assertEquals(false, saved.created)
        val listed = PairedWallets.list(session).single()
        assertEquals(setOf(PairFamily.EVM, PairFamily.BITCOIN), listed.families)
        assertEquals("Demo", listed.name)
        assertEquals(8, listed.bitcoin.size)
    }

    @Test
    fun repairingBitcoinReplacesItsAccountsOnTheSameWallet() {
        val seed = PairFixtures.seed
        PairedWallets.save(session, pairingOf(BtcWatchOnly.cryptoAccountParts(PairFixtures.allAccounts(seed))), nowMs = 100L)

        val mainnetOnly = listOf(
            PairFixtures.account(seed, BtcNetwork.MAINNET, ScriptType.LEGACY),
            PairFixtures.account(seed, BtcNetwork.MAINNET, ScriptType.NESTED_SEGWIT),
            PairFixtures.account(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT),
            PairFixtures.account(seed, BtcNetwork.MAINNET, ScriptType.TAPROOT),
        )
        PairedWallets.save(session, pairingOf(BtcWatchOnly.cryptoAccountParts(mainnetOnly)), nowMs = 200L)

        val listed = PairedWallets.list(session)
        assertEquals(1, listed.size)
        assertEquals(mainnetOnly.map { it.descriptor }, listed.single().bitcoin.map { it.descriptor })
    }

    @Test
    fun seedsThatDifferArePairedAsSeparateWallets() {
        PairedWallets.save(session, pairingOf(PairingExport.urParts(HdWallet(PairFixtures.seed), name = "One")), nowMs = 1L)
        PairedWallets.save(session, pairingOf(PairingExport.urParts(HdWallet(PairFixtures.otherSeed), name = "Two")), nowMs = 2L)

        assertEquals(listOf("One", "Two"), PairedWallets.list(session).map { it.name })
    }

    @Test
    fun aStoredBitcoinPairingReadsBackExactly() {
        val accounts = PairFixtures.allAccounts(PairFixtures.seed)
        PairedWallets.save(session, pairingOf(BtcWatchOnly.cryptoAccountParts(accounts)), nowMs = 1L)

        val read = PairedWallets.list(session).single().bitcoin
        assertEquals(accounts.map { it.descriptor }, read.map { it.descriptor })
        assertEquals(accounts.map { it.receiveAddress(0) }, read.map { it.receiveAddress(0) })
        assertEquals(accounts.map { it.network }, read.map { it.network })
    }

    @Test
    fun deletingAWalletRemovesItsPairing() {
        val saved = PairedWallets.save(session, pairingOf(PairingExport.urParts(HdWallet(PairFixtures.seed), name = "Demo")), nowMs = 1L)
        assertNotNull(session.getRecord(saved.id, WalletRecordNames.META))

        PairedWallets.delete(session, saved.id)

        assertTrue(PairedWallets.list(session).isEmpty())
        assertNull(session.getRecord(saved.id, WalletRecordNames.META))
    }
}
