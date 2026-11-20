package app.lensly.ui.home

import app.airvault.chains.bitcoin.signer.bitcoinManifest
import app.lensly.app.WalletStore
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.Mnemonic
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A bulk run creates the accounts the screen chose: the Script Type the owner picked is the one the accounts are derived with, on its
 * own purpose path, and numbered after the last account of that Script Type the wallet holds. An Ethereum run is unchanged.
 */
class BulkRunTest {
    private val keys = MemoryKeystore()

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"
    private val cap = BulkCap.DEFAULT.accounts
    private lateinit var session: VaultSession
    private lateinit var store: WalletStore

    @Before
    fun openVault() {
        session = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it + 7).toByte() })
        store = WalletStore(session)
    }

    @After
    fun closeVault() {
        session.close()
    }

    private fun newWallet(): String = store.create(
        name = "Test",
        deviceGenerated = false,
        words = words,
        passphrase = null,
        nowMs = 1L,
    )

    @Test
    fun aTaprootRunMakesTaprootAccountsOnTheBip86Path() {
        val id = newWallet()

        val saved = runBulk(store, id, DEFAULT_BITCOIN.copy(scriptType = ScriptType.TAPROOT), 3, cap) {}

        assertTrue(saved)
        val held = store.bitcoinAccounts(id)
        assertEquals(listOf(0, 1, 2), held.map { it.index })
        val seed = Mnemonic.toSeed(words)
        for (account in held) {
            assertEquals(ScriptType.TAPROOT, account.scriptType)
            assertEquals(BtcNetwork.MAINNET, account.network)
            val expected = BtcAccount.fromSeed(seed, BtcNetwork.MAINNET, ScriptType.TAPROOT, account.index)
            assertEquals("m/86'/0'/${account.index}'", expected.path.asString('\''))
            assertEquals(expected.receiveAddress(0), account.firstReceiveAddress)
            assertTrue(account.firstReceiveAddress.startsWith("bc1p"))
        }
    }

    @Test
    fun eachScriptTypeNumbersItsAccountsFromTheNextFreeIndexOfThatType() {
        val id = newWallet()
        val mainnet = { type: ScriptType -> DEFAULT_BITCOIN.copy(scriptType = type) }

        runBulk(store, id, mainnet(ScriptType.NATIVE_SEGWIT), 2, cap) {}
        runBulk(store, id, mainnet(ScriptType.TAPROOT), 2, cap) {}
        runBulk(store, id, mainnet(ScriptType.NATIVE_SEGWIT), 1, cap) {}

        val held = store.bitcoinAccounts(id)
        assertEquals(listOf(0, 1, 2), held.filter { it.scriptType == ScriptType.NATIVE_SEGWIT }.map { it.index })
        assertEquals(listOf(0, 1), held.filter { it.scriptType == ScriptType.TAPROOT }.map { it.index })
    }

    @Test
    fun everyScriptTypeTheScreenOffersMakesItsOwnAddressKind() {
        val id = newWallet()
        val seed = Mnemonic.toSeed(words)

        val testnet = bitcoinManifest.networks.single { it.testnet }
        for (type in ScriptType.entries) {
            assertTrue(runBulk(store, id, DEFAULT_BITCOIN.copy(network = testnet, scriptType = type), 1, cap) {})
        }

        val held = store.bitcoinAccounts(id).filter { it.network == BtcNetwork.TESTNET4 }
        assertEquals(ScriptType.entries.toSet(), held.map { it.scriptType }.toSet())
        for (account in held) {
            val expected = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, account.scriptType, 0)
            assertEquals(expected.receiveAddress(0), account.firstReceiveAddress)
        }
    }

    @Test
    fun anEthereumRunIsUnchanged() {
        val id = newWallet()

        assertTrue(runBulk(store, id, DEFAULT_EVM, 2, cap) {})

        assertEquals(3, store.meta(id)?.accountCount)
        assertTrue(store.bitcoinAccounts(id).isEmpty())
    }
}
