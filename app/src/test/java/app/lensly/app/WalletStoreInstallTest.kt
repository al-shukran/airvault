package app.lensly.app

import app.airvault.core.model.AppId
import app.lensly.chains.signerRegistry
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.vault.WalletRecordNames
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Installing and uninstalling the Apps of a wallet. An App's accounts derive from the seed, so an uninstalled App installs again with the same
 * addresses, and an Ethereum account keeps its name, label and group. The secret is never touched.
 */
class WalletStoreInstallTest {
    private val keys = MemoryKeystore()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var session: VaultSession
    private lateinit var store: WalletStore

    @Before
    fun openVault() {
        session = openSession()
        store = WalletStore(session)
    }

    @After
    fun closeVault() {
        session.close()
    }

    @Test
    fun installingEthereumMakesAccountZeroAtTheLedgerLiveAddress() {
        val id = newWallet()

        store.installApp(id, WalletApp.ETHEREUM)

        assertEquals(setOf(WalletApp.BITCOIN, WalletApp.ETHEREUM), store.installedApps(id))
        assertEquals(1, assertNotNull(store.meta(id)).accountCount)
        // Ethereum: account 0 is the Ledger Live address m/44'/60'/0'/0/0 of the phrase, pinned by HdWalletTest.
        assertEquals(listOf(LEDGER_LIVE_ETHEREUM_0), store.ethereumAccounts(id)?.addresses)
    }

    @Test
    fun installingSolanaAndAlgorandMakesAccountZeroFromEachAppsDerivation() {
        val id = newWallet()

        store.installApp(id, WalletApp.SOLANA)
        store.installApp(id, WalletApp.ALGORAND)

        val seed = seed()
        assertEquals(listOf(hex(SolanaAccount.publicKey(seed, 0))), store.solanaKeys(id).map { hex(it) })
        assertEquals(listOf(hex(AlgorandAccount.publicKey(seed, 0))), store.algorandKeys(id).map { hex(it) })
    }

    @Test
    fun installingAnAppThatIsInstalledAddsNoAccount() {
        val id = newWallet()
        val before = accountsOf(WalletApp.BITCOIN, id)

        store.installApp(id, WalletApp.BITCOIN)

        assertEquals(before, accountsOf(WalletApp.BITCOIN, id))
        assertEquals(1, store.bitcoinAccounts(id).size)
    }

    @Test
    fun uninstallingAnAppRemovesItsAccountsAndInstallingItAgainRestoresTheSameAddresses() {
        val id = newWallet()
        WalletApp.entries.forEach { store.installApp(id, it) }

        WalletApp.entries.forEach { app ->
            val before = accountsOf(app, id)
            assertTrue(before.isNotEmpty(), "$app has accounts before it is uninstalled.")

            store.uninstallApp(id, app)
            assertFalse(app in store.installedApps(id), "$app is uninstalled.")
            assertTrue(accountsOf(app, id).isEmpty(), "$app has no accounts once it is uninstalled.")

            store.installApp(id, app)
            assertEquals(before, accountsOf(app, id), "$app keeps its addresses when it is installed again.")
        }
    }

    @Test
    fun uninstallingEthereumKeepsTheNamesLabelsAndGroupsOfItsAccountsAndInstallingItAgainBringsThemBack() {
        val id = newWallet()
        store.installApp(id, WalletApp.ETHEREUM)
        val profile = AccountProfile(name = "Savings", label = "Cold", group = "Family")
        store.saveAccountProfile(id, 0, profile)

        store.uninstallApp(id, WalletApp.ETHEREUM)

        // The metadata still reads, with no Ethereum account, and the profile record stays.
        assertEquals(0, assertNotNull(store.meta(id)).accountCount)
        assertNotNull(session.getRecord(id, WalletRecords.accountRecord(0)))
        assertEquals(emptyMap<Int, AccountProfile>(), store.ethereumAccounts(id)?.profiles)

        store.installApp(id, WalletApp.ETHEREUM)
        assertEquals(mapOf(0 to profile), store.ethereumAccounts(id)?.profiles)
        assertEquals(LEDGER_LIVE_ETHEREUM_0, store.ethereumAccounts(id)?.addresses?.single())
    }

    @Test
    fun deletingAWalletWhoseEthereumWasUninstalledErasesTheKeptProfiles() {
        val id = newWallet()
        store.installApp(id, WalletApp.ETHEREUM)
        store.renameAccount(id, 0, "Savings")
        store.uninstallApp(id, WalletApp.ETHEREUM)
        assertNotNull(session.getRecord(id, WalletRecords.accountRecord(0)))

        store.delete(id)

        assertNull(session.getRecord(id, WalletRecords.accountRecord(0)))
        assertEquals(emptyList(), store.list().map { it.id })
    }

    @Test
    fun uninstallingEveryAppLeavesTheWalletListedWithItsSecret() {
        val id = newWallet()
        WalletApp.entries.forEach { store.installApp(id, it) }
        val secret = assertNotNull(session.getRecord(id, WalletRecords.SECRET)).toList()

        WalletApp.entries.forEach { store.uninstallApp(id, it) }

        assertEquals(emptySet(), store.installedApps(id))
        assertEquals(listOf(id), store.list().map { it.id })
        assertEquals(0, store.list().single().accountCount)
        assertEquals(secret, session.getRecord(id, WalletRecords.SECRET)?.toList())
    }

    @Test
    fun thePairingExcludesAnAppThatIsNotInstalled() {
        val id = newWallet()
        WalletApp.entries.forEach { store.installApp(id, it) }

        store.uninstallApp(id, WalletApp.SOLANA)

        val paired = pairingOf(store.pairingParts(id, null)).accounts
            .groupingBy { signerRegistry.byChain(it.export.chain)?.manifest?.id }
            .eachCount()
        val expected: Map<AppId?, Int> = WalletApp.entries.filter { it != WalletApp.SOLANA }.associate { it.appId to 1 }
        assertEquals(expected, paired)
    }

    @Test
    fun aSingleKeyWalletHasOnlyEthereumAndRefusesTheOtherApps() {
        val id = store.createSingleKey(name = "Imported", privateKey = ByteArray(32) { 1 }, nowMs = 5L)

        assertEquals(setOf(WalletApp.ETHEREUM), store.installedApps(id))
        store.installApp(id, WalletApp.ETHEREUM)
        assertFailsWith<IllegalArgumentException> { store.installApp(id, WalletApp.BITCOIN) }
        assertFailsWith<IllegalArgumentException> { store.uninstallApp(id, WalletApp.ETHEREUM) }
        assertEquals(setOf(WalletApp.ETHEREUM), store.installedApps(id))
    }

    @Test
    fun ethereumPairingRefusesAWalletWithoutEthereumAndWritesNothing() {
        val id = newWallet()

        assertFailsWith<AppNotInstalled> { store.ethereumPairingParts(id, "Demo") }

        assertEquals(0, assertNotNull(store.meta(id)).accountCount)
        assertEquals(setOf(WalletApp.BITCOIN), store.installedApps(id))
    }

    @Test
    fun anAirVaultPairingRefusesAWalletWithNoAppAndBuildsNoCode() {
        val id = newWallet()
        store.uninstallApp(id, WalletApp.BITCOIN)

        assertFailsWith<NoAppsInstalled> { store.pairingParts(id, null) }

        assertEquals(emptySet(), store.installedApps(id))
    }

    @Test
    fun aWalletWithTheRecordsLensly120WroteOpensWithAllFourInstalled() {
        // The records Lensly 1.2.0 wrote for a new wallet: one account of each App at index 0. They are written here directly, without the
        // install code under test, and reading them back must show all four Apps installed with the same addresses.
        val seed = seed()
        val id = session.createWallet()
        session.putRecord(id, WalletRecords.SECRET, WalletRecords.encodeSecret(DEMO_WORDS, null))
        session.putRecord(id, WalletRecords.BASE_NAME, WalletRecords.encodeName("Demo"))
        val wallet = HdWallet(seed)
        val meta = WalletMeta(
            id = id,
            name = "Demo",
            createdAtMs = 1L,
            deviceGenerated = false,
            accountCount = 1,
            masterFingerprintHex = wallet.masterFingerprintHex,
            accountKeys = listOf(wallet.accountXpub(0)),
        )
        session.putRecord(id, WalletRecords.META, WalletRecords.encodeMeta(meta))
        val bitcoin = BitcoinAccountRecord(
            network = BtcNetwork.MAINNET,
            scriptType = ScriptType.NATIVE_SEGWIT,
            index = 0,
            firstReceiveAddress = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, 0).receiveAddress(0),
        )
        session.putRecord(id, WalletRecords.BITCOIN, WalletRecords.encodeBitcoinAccounts(listOf(bitcoin)))
        session.putRecord(id, WalletRecordNames.SOLANA, WalletRecords.encodeSolanaKeys(listOf(SolanaAccount.publicKey(seed, 0))))
        session.putRecord(id, WalletRecordNames.ALGORAND, WalletRecords.encodeAlgorandKeys(listOf(AlgorandAccount.publicKey(seed, 0))))

        session.close()
        session = openSession()
        store = WalletStore(session)

        assertEquals(WalletApp.entries.toSet(), store.installedApps(id))
        assertEquals(
            mapOf(WalletApp.BITCOIN to 1, WalletApp.ETHEREUM to 1, WalletApp.SOLANA to 1, WalletApp.ALGORAND to 1),
            store.accountCounts(id),
        )
        assertEquals(listOf(bitcoin.firstReceiveAddress), accountsOf(WalletApp.BITCOIN, id))
        assertEquals(listOf(LEDGER_LIVE_ETHEREUM_0), accountsOf(WalletApp.ETHEREUM, id))
        assertEquals(listOf(hex(SolanaAccount.publicKey(seed, 0))), accountsOf(WalletApp.SOLANA, id))
        assertEquals(listOf(hex(AlgorandAccount.publicKey(seed, 0))), accountsOf(WalletApp.ALGORAND, id))
        assertEquals(listOf(id), store.list().map { it.id })
    }

    @Test
    fun uninstallingOneAppLeavesTheSecretRecordAsItWas() {
        val id = newWallet()
        WalletApp.entries.forEach { store.installApp(id, it) }
        val secret = assertNotNull(session.getRecord(id, WalletRecords.SECRET)).toList()

        WalletApp.entries.forEach { app ->
            store.uninstallApp(id, app)
            assertEquals(secret, session.getRecord(id, WalletRecords.SECRET)?.toList(), "The secret after $app is uninstalled.")
        }
    }

    @Test
    fun aBitcoinOnlyWalletRestoresItsBitcoinAccountAndLeavesADecoyWalletUntouched() {
        val decoy = store.create(name = "Decoy", deviceGenerated = false, words = DEMO_WORDS, passphrase = "decoy", nowMs = 2L)
        WalletApp.entries.forEach { store.installApp(decoy, it) }
        val decoyBefore = WalletApp.entries.associateWith { accountsOf(it, decoy) }
        val id = newWallet()
        val before = accountsOf(WalletApp.BITCOIN, id)

        store.uninstallApp(id, WalletApp.BITCOIN)
        assertEquals(emptySet(), store.installedApps(id))
        assertEquals(setOf(id, decoy), store.list().map { it.id }.toSet())

        store.installApp(id, WalletApp.BITCOIN)
        assertEquals(setOf(WalletApp.BITCOIN), store.installedApps(id))
        assertEquals(before, accountsOf(WalletApp.BITCOIN, id), "Bitcoin derives the same address again.")
        assertEquals(decoyBefore, WalletApp.entries.associateWith { accountsOf(it, decoy) }, "The decoy keeps every App's accounts.")
        assertEquals(WalletApp.entries.toSet(), store.installedApps(decoy))
    }

    @Test
    fun aRefusedInstallOrUninstallLeavesTheAppSetAndTheAccountCountsAsTheyWere() {
        val id = store.createSingleKey(name = "Imported", privateKey = ByteArray(32) { 1 }, nowMs = 5L)
        val counts = store.accountCounts(id)
        val secret = assertNotNull(session.getRecord(id, WalletRecords.SECRET)).toList()

        assertFailsWith<IllegalArgumentException> { store.installApp(id, WalletApp.BITCOIN) }
        assertFailsWith<IllegalArgumentException> { store.uninstallApp(id, WalletApp.ETHEREUM) }

        assertEquals(setOf(WalletApp.ETHEREUM), store.installedApps(id))
        assertEquals(counts, store.accountCounts(id))
        assertEquals(secret, session.getRecord(id, WalletRecords.SECRET)?.toList())
    }

    @Test
    fun addingAnEthereumAccountToAWalletWithoutEthereumIsRefusedAndWritesNothing() {
        val id = newWallet()

        assertFailsWith<AppNotInstalled> { store.addAccount(id) }
        assertFailsWith<AppNotInstalled> { store.addAccounts(id, count = 1, cap = 1) }

        assertEquals(0, assertNotNull(store.meta(id)).accountCount)
        assertEquals(setOf(WalletApp.BITCOIN), store.installedApps(id))
    }

    private fun newWallet(): String = store.create(name = "Demo", deviceGenerated = false, words = DEMO_WORDS, passphrase = null, nowMs = 1L)

    private fun openSession(): VaultSession = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it + 17).toByte() })

    private fun seed(): ByteArray = Mnemonic.of(DEMO_WORDS).use { it.deriveSeed("") }

    /** The addresses or public keys of [app]'s accounts in wallet [id], in account order. */
    private fun accountsOf(app: WalletApp, id: String): List<String> = when (app) {
        WalletApp.BITCOIN -> store.bitcoinAccounts(id).map { it.firstReceiveAddress }
        WalletApp.ETHEREUM -> store.ethereumAccounts(id)?.addresses.orEmpty()
        WalletApp.SOLANA -> store.solanaKeys(id).map { hex(it) }
        WalletApp.ALGORAND -> store.algorandKeys(id).map { hex(it) }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        val DEMO_WORDS = List(23) { "abandon" } + "art"

        /** The Ledger Live address of account 0 for [DEMO_WORDS], as HdWalletTest pins it. */
        const val LEDGER_LIVE_ETHEREUM_0 = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb"
    }
}
