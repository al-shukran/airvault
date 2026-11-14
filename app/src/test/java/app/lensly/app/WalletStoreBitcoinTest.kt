package app.lensly.app

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnly
import app.lensly.core.btc.ScriptType
import app.lensly.core.ur.UrScanner
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.Mnemonic
import com.sparrowwallet.hummingbird.UREncoder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Bitcoin accounts of a wallet: the first receive address against the published vectors, one account per Script Type, network
 * and index, a Single Key refused with its reason, and the watch-only export.
 */
class WalletStoreBitcoinTest {
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
    fun firstReceiveAddressesMatchThePublishedVectors() {
        // The BIP39 seed of the published "abandon abandon ... about" mnemonic, with no passphrase. The BIP84 and BIP86 vectors
        // are derived from it.
        val seed = hex(PUBLISHED_SEED)
        val native = bitcoinAccountRecords(seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, 0..0).single()
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", native.firstReceiveAddress)
        val taproot = bitcoinAccountRecords(seed, BtcNetwork.MAINNET, ScriptType.TAPROOT, 0..0).single()
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", taproot.firstReceiveAddress)
        // The testnet4 Nested Segwit row of the shared vectors file.
        val nested = bitcoinAccountRecords(seed, BtcNetwork.TESTNET4, ScriptType.NESTED_SEGWIT, 0..0).single()
        assertEquals("2Mww8dCYPUpKHofjgcXcBCEGmniw9CoaiD2", nested.firstReceiveAddress)
    }

    @Test
    fun aStoredAddressIsTheFirstReceiveAddressOfTheWalletsOwnAccount() {
        val id = newWallet()

        val added = assertNotNull(store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 1, cap))

        val expected = BtcAccount.fromSeed(Mnemonic.toSeed(words), BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, 0)
            .receiveAddress(0)
        assertEquals(expected, added.single().firstReceiveAddress)
        assertEquals(added, store.bitcoinAccounts(id))
    }

    @Test
    fun aWalletHasNoBitcoinAccountUntilOneIsAdded() {
        val id = newWallet()

        assertTrue(store.bitcoinAccounts(id).isEmpty())
        assertFailsWith<IllegalArgumentException> { store.bitcoinWatchOnlyParts(id) }
    }

    @Test
    fun aRunNumbersEachScriptTypeAndNetworkFromZeroAndHoldsEachKeyOnce() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 3, cap)
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 2, cap)
        store.addBitcoinAccounts(id, ScriptType.TAPROOT, BtcNetwork.MAINNET, 1, cap)
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 2, cap)

        val held = store.bitcoinAccounts(id)
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            held.filter { it.scriptType == ScriptType.NATIVE_SEGWIT && it.network == BtcNetwork.MAINNET }.map { it.index },
        )
        assertEquals(listOf(0, 1), held.filter { it.network == BtcNetwork.TESTNET4 }.map { it.index })
        assertEquals(listOf(0), held.filter { it.scriptType == ScriptType.TAPROOT }.map { it.index })
        assertEquals(held.size, held.map { it.key }.toSet().size)
    }

    @Test
    fun aRecordNeverHoldsOneKeyTwice() {
        val account = BitcoinAccountRecord(BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT, 0, "bc1qexample")
        val twin = account.copy(firstReceiveAddress = "bc1qother")
        assertFailsWith<IllegalArgumentException> { WalletRecords.encodeBitcoinAccounts(listOf(account, twin)) }

        // A record that a writer put together with a repeated key is refused when it is read too.
        val repeated = WalletRecords.writeBytes { out ->
            out.writeByte(1)
            out.writeInt(2)
            repeat(2) {
                out.writeByte(BtcNetwork.MAINNET.coinType)
                out.writeByte(ScriptType.NATIVE_SEGWIT.purpose)
                out.writeInt(0)
                out.writeUTF("bc1qexample")
            }
        }
        assertFailsWith<IllegalArgumentException> { WalletRecords.decodeBitcoinAccounts(repeated) }
    }

    @Test
    fun aWalletKeepsAtMostOneHundredBitcoinAccounts() {
        val id = newWallet()
        val hundred = BulkCap.HUNDRED.accounts
        assertEquals(100, store.addBitcoinAccounts(id, ScriptType.TAPROOT, BtcNetwork.MAINNET, 100, hundred)?.size)

        assertFailsWith<IllegalArgumentException> {
            store.addBitcoinAccounts(id, ScriptType.TAPROOT, BtcNetwork.MAINNET, 1, hundred)
        }
        assertEquals(100, store.bitcoinAccounts(id).size)
    }

    @Test
    fun aSingleKeyWalletRefusesBitcoinAndSaysWhy() {
        val id = store.createSingleKey(name = "Key", privateKey = ByteArray(32) { 1 }, nowMs = 1L)

        val refusal = assertFailsWith<IllegalArgumentException> {
            store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 1, cap)
        }
        assertEquals(SINGLE_KEY_NO_BITCOIN, refusal.message)
        assertTrue(store.bitcoinAccounts(id).isEmpty())
        assertFailsWith<IllegalArgumentException> { store.bitcoinWatchOnlyParts(id) }
    }

    @Test
    fun aRunAboveTheBulkCapIsRefusedAndSavesNothing() {
        val id = newWallet()

        assertFailsWith<IllegalArgumentException> {
            store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 21, cap = 20)
        }
        assertTrue(store.bitcoinAccounts(id).isEmpty())
    }

    @Test
    fun theWatchOnlyExportCarriesEveryScriptTypeOfEachNetworkTheWalletHolds() {
        val id = newWallet()
        store.addBitcoinAccounts(id, ScriptType.NATIVE_SEGWIT, BtcNetwork.MAINNET, 1, cap)
        store.addBitcoinAccounts(id, ScriptType.TAPROOT, BtcNetwork.TESTNET4, 1, cap)

        val scanner = UrScanner()
        store.bitcoinWatchOnlyParts(id).forEach { scanner.receive(it) }
        val ur = assertNotNull(scanner.result())
        val accounts = BtcWatchOnly.parseCryptoAccount(UREncoder.encode(ur))

        assertEquals(8, accounts.size)
        val allTypes = ScriptType.entries.toSet()
        assertEquals(allTypes, accounts.filter { it.network == BtcNetwork.MAINNET }.map { it.scriptType }.toSet())
        assertEquals(allTypes, accounts.filter { it.network == BtcNetwork.TESTNET4 }.map { it.scriptType }.toSet())
    }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { i ->
        text.substring(2 * i, 2 * i + 2).toInt(16).toByte()
    }

    private companion object {
        /** The 64-byte BIP39 seed of the "abandon ... about" mnemonic, as app.lensly.core.btc's vectors file has it. */
        const val PUBLISHED_SEED =
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
    }
}
