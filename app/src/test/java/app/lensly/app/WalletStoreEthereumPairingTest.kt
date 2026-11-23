package app.lensly.app

import app.lensly.app.export.importedAccounts
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val WORDS = List(23) { "abandon" } + "art"

/**
 * The Ledger Live addresses m/44'/60'/N'/0/0 of "abandon x23 art" for N = 0 until 10, from an independent implementation:
 * ethers 6.13.4, `HDNodeWallet.fromPhrase(phrase, undefined, "m/44'/60'/N'/0/0").address`.
 */
private val LEDGER_LIVE = listOf(
    "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb",
    "0x94142B4f665316D3304C3a595ec83aC9C8046598",
    "0xe93E76dFCc3Bb3A6A3A36Bcd9df68cb7C0AD46AA",
    "0x71C2332fc061939f415271D52D24b32f3D4A9173",
    "0x290C2D6fa111595Df8A636d36763bFe4cD8D9e6F",
    "0x0EaC10e6DFD25B1F87daBBB504A91bd2f5DD34b6",
    "0x398168EF43285e9de15c8008A4F488fA1C0b874b",
    "0x0e1aaBaa73dB347473Abb30AD478ea8b716dc264",
    "0x671c188661Bb3FBe0eBb6a59324C5A1A5A232D28",
    "0xd2eE9e21bb372E9944D9D81Cdee4c3Ac4fAF98d2",
)

/** The Rabby and MetaMask pairing lists exactly the wallet's accounts, as Lensly shows them, and completes the last page of five (#822). */
class WalletStoreEthereumPairingTest {
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

    private fun walletWith(accounts: Int): String {
        val id = store.create(name = "Demo", deviceGenerated = false, words = WORDS, passphrase = null, nowMs = 1L)
        repeat(accounts - 1) { store.addAccount(id) }
        return id
    }

    private fun shownAddresses(id: String) = List(assertNotNull(store.meta(id)).accountCount) { store.accountAddress(id, it) }

    @Test
    fun aNewWalletGetsItsFirstPageAndTheImporterListsItsAccounts() {
        val id = walletWith(1)
        val imported = importedAccounts(store.ethereumPairingParts(id, "Demo"))

        assertEquals(5, assertNotNull(store.meta(id)).accountCount)
        assertEquals(LEDGER_LIVE.take(5), imported.map { it.address })
        assertEquals(shownAddresses(id), imported.map { it.address })
    }

    @Test
    fun aWalletPastAPageGetsTheRestOfItsLastPage() {
        val id = walletWith(6)
        val imported = importedAccounts(store.ethereumPairingParts(id, "Demo"))

        assertEquals(10, assertNotNull(store.meta(id)).accountCount)
        assertEquals(LEDGER_LIVE, imported.map { it.address })
        assertEquals(shownAddresses(id), imported.map { it.address })
    }

    @Test
    fun aWholePageIsLeftAsItIs() {
        val id = walletWith(5)
        store.ethereumPairingParts(id, "Demo")
        assertEquals(5, assertNotNull(store.meta(id)).accountCount)
    }
}
