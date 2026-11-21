package app.lensly.app

import app.airvault.core.model.AppId
import app.lensly.app.export.PairingCodec
import app.lensly.app.export.PairingPayload
import app.lensly.chains.installedSigners
import app.lensly.chains.signerRegistry
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.ur.UrScanner
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.Mnemonic
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A new wallet, created or restored from its words, holds one account of each App at index 0, on the derivation that App's bulk run starts
 * on. Ethereum is checked against the Ledger Live address of the demo phrase, and each other App against its own derivation of that seed.
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
    fun aNewWalletHasOneAccountOfEachAppAtIndexZero() {
        val id = store.create(name = "Demo", deviceGenerated = false, words = DEMO_WORDS, passphrase = null, nowMs = 1L)
        val seed = Mnemonic.of(DEMO_WORDS).use { it.deriveSeed("") }

        assertEquals(1, assertNotNull(store.meta(id)).accountCount)
        // Ethereum: account 0 is the Ledger Live address m/44'/60'/0'/0/0 of the phrase, pinned by HdWalletTest.
        assertEquals(listOf(LEDGER_LIVE_ETHEREUM_0), store.ethereumAccounts(id)?.addresses)
        // Bitcoin: Native Segwit on mainnet, index 0, with the first receive address of that account.
        val bitcoin = store.bitcoinAccounts(id).single()
        assertEquals(BtcNetwork.MAINNET, bitcoin.network)
        assertEquals(ScriptType.NATIVE_SEGWIT, bitcoin.scriptType)
        assertEquals(0, bitcoin.index)
        assertEquals(BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, 0).receiveAddress(0), bitcoin.firstReceiveAddress)
        // Solana and Algorand: the key of account 0 from each App's own derivation.
        assertEquals(listOf(SolanaAccount.publicKey(seed, 0).toList()), store.solanaKeys(id).map { it.toList() })
        assertEquals(listOf(AlgorandAccount.publicKey(seed, 0).toList()), store.algorandKeys(id).map { it.toList() })
    }

    @Test
    fun aNewWalletHasOneAccountPerInstalledChainApp() {
        val id = store.create(name = "Demo", deviceGenerated = false, words = DEMO_WORDS, passphrase = null, nowMs = 1L)

        // Every installed chain app is a WalletApp, and each WalletApp holds exactly one account of the new wallet.
        assertEquals(installedIds(), WalletApp.entries.map { it.appId }.toSet())
        val created = WalletApp.entries.associate { app -> app.appId to accountCountOf(app, id) }
        installedIds().forEach { appId -> assertEquals(1, created[appId], "The new wallet's accounts of $appId.") }
    }

    @Test
    fun thePairingHoldsOneAccountPerInstalledChainApp() {
        val id = store.create(name = "Demo", deviceGenerated = false, words = DEMO_WORDS, passphrase = null, nowMs = 1L)

        // Each paired account is matched to the installed app that serves its chain. None may go unmatched.
        val paired = pairingOf(store.pairingParts(id, null)).accounts
            .groupingBy { signerRegistry.byChain(it.export.chain)?.manifest?.id }
            .eachCount()
        assertFalse(paired.containsKey(null), "A paired account is on no installed chain.")
        installedIds().forEach { appId -> assertEquals(1, paired[appId], "The pairing's accounts of $appId.") }
    }

    private fun installedIds(): Set<AppId> = installedSigners.map { it.manifest.id }.toSet()

    private fun accountCountOf(app: WalletApp, id: String): Int = when (app) {
        WalletApp.BITCOIN -> store.bitcoinAccounts(id).size
        WalletApp.ETHEREUM -> store.ethereumAccounts(id)?.addresses?.size ?: 0
        WalletApp.SOLANA -> store.solanaKeys(id).size
        WalletApp.ALGORAND -> store.algorandKeys(id).size
    }

    /** The pairing that [parts] carry, read as the online companion reads it. */
    private fun pairingOf(parts: List<String>): PairingPayload {
        val scanner = UrScanner()
        parts.forEach { scanner.receive(it) }
        return PairingCodec.fromUr(assertNotNull(scanner.result()), null)
    }

    private companion object {
        val DEMO_WORDS = List(23) { "abandon" } + "art"

        /** The Ledger Live address of account 0 for [DEMO_WORDS], as HdWalletTest pins it. */
        const val LEDGER_LIVE_ETHEREUM_0 = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb"
    }
}
