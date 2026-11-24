package app.lensly.app

import app.airvault.core.model.AppId
import app.lensly.chains.installedSigners
import app.lensly.chains.signerRegistry
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.Mnemonic
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A new wallet, created or restored from its words, holds one Bitcoin account at index 0 and no other App. The other Apps are installed
 * afterwards. The Bitcoin account is checked against its own derivation of the seed, and the wallet's pairing carries that account only.
 */
class WalletStoreFirstAccountsTest {
    private val keys = MemoryKeystore()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var session: VaultSession
    private lateinit var store: WalletStore

    @Before
    fun openVault() {
        session = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it + 13).toByte() })
        store = WalletStore(session)
    }

    @After
    fun closeVault() {
        session.close()
    }

    @Test
    fun aNewWalletHasOneBitcoinAccountAtIndexZeroAndNoOtherApp() {
        val id = store.create(name = "Demo", deviceGenerated = false, words = DEMO_WORDS, passphrase = null, nowMs = 1L)
        val seed = Mnemonic.of(DEMO_WORDS).use { it.deriveSeed("") }

        assertEquals(setOf(WalletApp.BITCOIN), store.installedApps(id))
        assertEquals(0, assertNotNull(store.meta(id)).accountCount)
        assertEquals(0, store.ethereumAccounts(id)?.addresses?.size ?: 0)
        assertTrue(store.solanaKeys(id).isEmpty())
        assertTrue(store.algorandKeys(id).isEmpty())
        // Bitcoin: Native Segwit on mainnet, index 0, with the first receive address of that account.
        val bitcoin = store.bitcoinAccounts(id).single()
        assertEquals(BtcNetwork.MAINNET, bitcoin.network)
        assertEquals(ScriptType.NATIVE_SEGWIT, bitcoin.scriptType)
        assertEquals(0, bitcoin.index)
        assertEquals(BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, 0).receiveAddress(0), bitcoin.firstReceiveAddress)
    }

    @Test
    fun thePairingOfANewWalletCarriesOnlyItsBitcoinAccount() {
        val id = store.create(name = "Demo", deviceGenerated = false, words = DEMO_WORDS, passphrase = null, nowMs = 1L)

        // Each paired account is matched to the installed app that serves its chain. None may go unmatched.
        val paired = pairingOf(store.pairingParts(id, null)).accounts
            .groupingBy { signerRegistry.byChain(it.export.chain)?.manifest?.id }
            .eachCount()
        assertEquals(mapOf<AppId?, Int>(WalletApp.BITCOIN.appId to 1), paired)
    }

    @Test
    fun aDecoyWalletHasBitcoinOnly() {
        DecoyVault.populate(session)

        assertEquals(setOf(WalletApp.BITCOIN), store.installedApps(session.listWallets().single()))
    }

    @Test
    fun aRestoredWalletHasBitcoinOnly() {
        val id = store.create(name = "Restored", deviceGenerated = false, words = DEMO_WORDS, passphrase = null, nowMs = 1L, seedBackedUp = true)

        assertEquals(setOf(WalletApp.BITCOIN), store.installedApps(id))
    }

    @Test
    fun everyWalletAppIsAnInstalledChainApp() {
        assertEquals(installedSigners.map { it.manifest.id }.toSet(), WalletApp.entries.map { it.appId }.toSet())
    }

    private companion object {
        val DEMO_WORDS = List(23) { "abandon" } + "art"
    }
}
