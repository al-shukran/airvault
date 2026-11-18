package app.lensly.app

import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The accounts of the vault on every chain, the map that a review and the Activity detail name an address by: an Ethereum account by its
 * address, a Solana or Algorand account by the address of its key, and a Bitcoin account by its first receive address. A review of one
 * wallet names that wallet's accounts without a wallet name, and every other wallet's accounts with theirs.
 */
class InternalAccountsTest {
    private val keys = MemoryKeystore()

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"
    private val otherWords = List(23) { "zoo" } + "vote"
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

    private fun wallet(name: String, phrase: List<String> = words): String = store.create(
        name = name,
        deviceGenerated = false,
        words = phrase,
        passphrase = null,
        nowMs = 1L,
    )

    @Test
    fun anEthereumAccountIsNamedByItsDisplayNameAndLabel() {
        val id = wallet("Savings")
        store.addAccount(id)
        store.saveAccountProfile(id, 1, AccountProfile(name = "Trading", label = "Hot"))

        val accounts = internalAccountsOf(store, signingWalletId = id)

        assertEquals(InternalAccount("Trading", "Savings 1", "Hot"), accounts[addressKeyOf(store.accountAddress(id, 1))])
        assertEquals(InternalAccount(null, "Savings 0", null), accounts[addressKeyOf(store.accountAddress(id, 0))])
    }

    @Test
    fun aMultiAccountWalletNamesEachAccountByItsIndex() {
        val id = wallet("Vault")
        store.addAccount(id)
        store.addAccount(id)
        store.renameAccount(id, 1, "Trading")
        store.saveAccountProfile(id, 2, AccountProfile(label = "Cold"))

        val accounts = internalAccountsOf(store, signingWalletId = id)

        assertEquals(InternalAccount(null, "Vault 0", null), accounts[addressKeyOf(store.accountAddress(id, 0))])
        assertEquals(InternalAccount("Trading", "Vault 1", null), accounts[addressKeyOf(store.accountAddress(id, 1))])
        assertEquals(InternalAccount(null, "Vault 2", "Cold"), accounts[addressKeyOf(store.accountAddress(id, 2))])
    }

    @Test
    fun anEthereumAddressIsFoundInAnyLetterCase() {
        val id = wallet("Savings")
        val address = store.accountAddress(id, 0)
        val accounts = internalAccountsOf(store, signingWalletId = id)
        val expected = InternalAccount(null, "Savings 0", null)

        assertEquals(expected, addressNotesOf(address.lowercase(), AddressChain.EVM, accounts, emptyList())?.internal)
        assertEquals(expected, addressNotesOf(address.uppercase().replace("0X", "0x"), AddressChain.EVM, accounts, emptyList())?.internal)
    }

    @Test
    fun aSolanaAccountIsNamedByItsAutoName() {
        val id = wallet("Savings")
        val solana = assertNotNull(store.addSolanaAccounts(id, 2, cap))

        val accounts = internalAccountsOf(store, signingWalletId = id)

        assertEquals(InternalAccount(null, "Savings 1", null), accounts[addressKeyOf(SolanaAccount.addressOf(solana[1]))])
        assertNull(accounts[addressKeyOf(SolanaAccount.addressOf(solana[1])).lowercase()])
    }

    @Test
    fun anAlgorandAccountIsNamedByItsAutoName() {
        val id = wallet("Savings")
        val algorand = assertNotNull(store.addAlgorandAccounts(id, 2, cap))

        val accounts = internalAccountsOf(store, signingWalletId = id)

        assertEquals(InternalAccount(null, "Savings 1", null), accounts[addressKeyOf(AlgorandAccount.addressOf(algorand[1]))])
    }

    @Test
    fun everyBitcoinScriptTypeAndIndexHeldIsMatchedByItsFirstReceiveAddress() {
        val id = wallet("Savings")
        ScriptType.entries.forEach { scriptType ->
            assertNotNull(store.addBitcoinAccounts(id, scriptType, BtcNetwork.TESTNET4, 2, cap))
        }

        val accounts = internalAccountsOf(store, signingWalletId = id)

        val held = store.bitcoinAccounts(id)
        assertEquals(ScriptType.entries.size * 2, held.size)
        held.forEach { record ->
            assertEquals(
                InternalAccount(null, autoAccountName("Savings", record.index), null),
                accounts[addressKeyOf(record.firstReceiveAddress)],
                "${record.scriptType} ${record.index}",
            )
        }
    }

    @Test
    fun aBitcoinAccountOfAnotherWalletNamesThatWallet() {
        val id = wallet("Savings")
        val other = wallet("Trading", otherWords)
        assertNotNull(store.addBitcoinAccounts(other, ScriptType.NATIVE_SEGWIT, BtcNetwork.TESTNET4, 1, cap))
        val record = store.bitcoinAccounts(other).single()

        val accounts = internalAccountsOf(store, signingWalletId = id)

        assertEquals(InternalAccount(null, "Trading 0", null, walletName = "Trading"), accounts[addressKeyOf(record.firstReceiveAddress)])
    }

    @Test
    fun aSingleKeyWalletsAccountIsNamedByItsAutoName() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val id = store.createSingleKey(name = "Key", privateKey = key, nowMs = 1L)

        val accounts = internalAccountsOf(store, signingWalletId = null)

        assertEquals(InternalAccount(null, "Key 0", null, walletName = "Key"), accounts[addressKeyOf(store.accountAddress(id, 0))])
    }

    @Test
    fun theSigningWalletsAccountsCarryNoWalletNameAndEveryOtherWalletsDo() {
        val id = wallet("Savings")
        val other = wallet("Trading", otherWords)

        val accounts = internalAccountsOf(store, signingWalletId = id)

        assertNull(accounts[addressKeyOf(store.accountAddress(id, 0))]?.walletName)
        assertEquals("Trading", accounts[addressKeyOf(store.accountAddress(other, 0))]?.walletName)
    }

    @Test
    fun aNullSigningWalletNamesEveryWallet() {
        val id = wallet("Savings")

        val accounts = internalAccountsOf(store, signingWalletId = null)

        assertEquals("Savings", accounts[addressKeyOf(store.accountAddress(id, 0))]?.walletName)
    }
}
