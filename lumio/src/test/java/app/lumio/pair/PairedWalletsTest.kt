package app.lumio.pair

import app.lensly.app.AddressBookEntry
import app.lensly.app.autoAccountName
import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.btc.ScriptType
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.vault.WalletRecordNames
import app.lensly.core.wallet.HdWallet
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The paired wallets in an open vault. Lensly is the source of truth: a re-pair of the same fingerprint replaces the names, labels, groups,
 * accounts and keys that Lumio held, so a renamed account is renamed, a removed account is removed, and an added account is added.
 */
class PairedWalletsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()
    private val session by lazy {
        VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it * 7 + 3).toByte() })
    }

    @Test
    fun anEthereumPairingIsStoredAndListedWithItsNamesLabelsAndGroups() {
        val saved = PairedWallets.save(
            session,
            payload(accounts = listOf(account(0, "Savings", "cold", "Long term"), account(1, "Trading", null, "Hot"))),
            nowMs = 100L,
        )

        val listed = PairedWallets.list(session).single()

        assertTrue(saved.created)
        assertEquals(saved.id, listed.id)
        assertEquals("Demo", listed.name)
        assertEquals(
            listOf(
                PairedAccount(0, PairFixtures.address(PairFixtures.seed, 0), "Savings", "cold", "Long term"),
                PairedAccount(1, PairFixtures.address(PairFixtures.seed, 1), "Trading", null, "Hot"),
            ),
            listed.evmAccounts,
        )
        assertEquals(setOf(PairChain.EVM), listed.families)
        assertEquals(PairFixtures.address(PairFixtures.seed, 0), listed.evmAddresses.first())
    }

    @Test
    fun repairingTheSameFingerprintUpdatesTheWalletAndDoesNotDuplicateIt() {
        val first = PairedWallets.save(session, payload(name = "Demo", accounts = listOf(account(0, "Savings"))), nowMs = 100L)
        val second = PairedWallets.save(session, payload(name = "Renamed", accounts = listOf(account(0, "Savings"))), nowMs = 200L)

        val listed = PairedWallets.list(session)

        assertEquals(first.id, second.id)
        assertEquals(false, second.created)
        assertEquals(1, listed.size)
        assertEquals("Renamed", listed.single().name)
        assertEquals(100L, listed.single().createdAtMs)
    }

    @Test
    fun aRenamedAccountIsRenamedInLumioOnRepair() {
        PairedWallets.save(session, payload(accounts = listOf(account(0, "Savings"))), nowMs = 100L)

        PairedWallets.save(session, payload(accounts = listOf(account(0, "Cold store"))), nowMs = 200L)

        assertEquals(
            listOf(PairedAccount(0, PairFixtures.address(PairFixtures.seed, 0), "Cold store", null, null)),
            PairedWallets.list(session).single().evmAccounts,
        )
    }

    @Test
    fun labelsAndGroupsAreReplacedNotKeptOnRepair() {
        PairedWallets.save(session, payload(accounts = listOf(account(0, "Savings", "cold", "Long term"))), nowMs = 100L)

        PairedWallets.save(session, payload(accounts = listOf(account(0, "Savings"))), nowMs = 200L)

        assertEquals(
            listOf(PairedAccount(0, PairFixtures.address(PairFixtures.seed, 0), "Savings", null, null)),
            PairedWallets.list(session).single().evmAccounts,
        )
    }

    @Test
    fun anAccountLensllyRemovedIsRemovedOnRepair() {
        PairedWallets.save(
            session,
            payload(accounts = listOf(account(0, "A"), account(1, "B", "tag", "G"), account(2, "C"))),
            nowMs = 100L,
        )

        PairedWallets.save(session, payload(accounts = listOf(account(0, "A"), account(1, "B"))), nowMs = 200L)

        val listed = PairedWallets.list(session).single()
        assertEquals(listOf(0, 1), listed.evmAccounts.map { it.index })
        assertEquals(
            listOf(PairedAccount(1, PairFixtures.address(PairFixtures.seed, 1), "B", null, null)),
            listed.evmAccounts.filter { it.index == 1 },
        )
    }

    @Test
    fun anAccountLensllyAddedIsAddedOnRepair() {
        PairedWallets.save(session, payload(accounts = listOf(account(0, "A"))), nowMs = 100L)

        PairedWallets.save(session, payload(accounts = listOf(account(0, "A"), account(1, "B"), account(2, "C", "x", null))), nowMs = 200L)

        assertEquals(
            listOf(
                PairedAccount(0, PairFixtures.address(PairFixtures.seed, 0), "A", null, null),
                PairedAccount(1, PairFixtures.address(PairFixtures.seed, 1), "B", null, null),
                PairedAccount(2, PairFixtures.address(PairFixtures.seed, 2), "C", "x", null),
            ),
            PairedWallets.list(session).single().evmAccounts,
        )
    }

    @Test
    fun bitcoinForTheSameSeedJoinsTheWalletAndKeepsTheEthereumAddress() {
        PairedWallets.save(session, payload(accounts = listOf(account(0, "A"))), nowMs = 100L)

        PairedWallets.save(session, payload(accounts = listOf(account(0, "A")), bitcoin = PairFixtures.allAccounts(PairFixtures.seed)), nowMs = 200L)

        val listed = PairedWallets.list(session).single()
        assertEquals(setOf(PairChain.EVM, PairChain.BITCOIN), listed.families)
        assertEquals(8, listed.bitcoin.size)
        assertEquals(PairFixtures.address(PairFixtures.seed, 0), listed.evmAddresses.first())
    }

    @Test
    fun repairingWithoutBitcoinRemovesTheBitcoinAccountsItHeld() {
        PairedWallets.save(
            session,
            payload(accounts = listOf(account(0, "A")), bitcoin = PairFixtures.allAccounts(PairFixtures.seed)),
            nowMs = 100L,
        )

        PairedWallets.save(session, payload(accounts = listOf(account(0, "A"))), nowMs = 200L)

        val listed = PairedWallets.list(session).single()
        assertTrue(listed.bitcoin.isEmpty())
        assertEquals(setOf(PairChain.EVM), listed.families)
    }

    @Test
    fun aStoredBitcoinPairingReadsBackExactly() {
        val accounts = listOf(PairFixtures.account(PairFixtures.seed, BtcNetwork.TESTNET4, ScriptType.TAPROOT))
        PairedWallets.save(session, payload(accounts = listOf(account(0, "A")), bitcoin = accounts), nowMs = 1L)

        val read = PairedWallets.list(session).single().bitcoin.single()

        assertEquals(accounts.single().shape(), read.shape())
        assertEquals(accounts.single().receiveAddress(0), read.receiveAddress(0))
    }

    @Test
    fun seedsThatDifferArePairedAsSeparateWallets() {
        PairedWallets.save(session, payload(name = "One", accounts = listOf(account(0, "A"))), nowMs = 1L)
        PairedWallets.save(session, payload(name = "Two", accounts = listOf(account(0, "B")), seed = PairFixtures.otherSeed), nowMs = 2L)

        assertEquals(listOf("One", "Two"), PairedWallets.list(session).map { it.name })
    }

    @Test
    fun deletingAWalletRemovesItsPairing() {
        val saved = PairedWallets.save(session, payload(accounts = listOf(account(0, "A"))), nowMs = 1L)

        PairedWallets.delete(session, saved.id)

        assertTrue(PairedWallets.list(session).isEmpty())
    }

    @Test
    fun aRecordOfAnotherVersionIsKeptAndReadsAsUnpaired() {
        val saved = PairedWallets.save(session, payload(accounts = listOf(account(0, "A"))), nowMs = 1L)
        val meta = session.getRecord(saved.id, WalletRecordNames.META)!!
        meta[0] = (meta[0] - 1).toByte()
        session.putRecords(saved.id, mapOf(WalletRecordNames.META to meta), emptyList())

        assertTrue(PairedWallets.list(session).isEmpty())
        assertNull(PairedWallets.get(session, saved.id))
        assertNotNull(session.getRecord(saved.id, WalletRecordNames.META))
        val other = PairedWallets.save(session, payload(name = "Other", accounts = listOf(account(0, "B")), seed = PairFixtures.otherSeed), nowMs = 2L)
        assertEquals(setOf(saved.id, other.id), session.listWallets().toSet())
        assertEquals(listOf("Other"), PairedWallets.list(session).map { it.name })
    }

    @Test
    fun getReadsTheAskedWalletWithItsOwnAccounts() {
        PairedWallets.save(session, payload(name = "One", accounts = listOf(account(0, "A"))), nowMs = 1L)
        val two = PairedWallets.save(session, payload(name = "Two", accounts = listOf(account(0, "B")), seed = PairFixtures.otherSeed), nowMs = 2L)

        val wallet = PairedWallets.get(session, two.id)

        assertEquals("Two", wallet?.name)
        assertEquals(listOf("B"), wallet?.evmAccounts?.map { it.name })
    }

    @Test
    fun getOfAFolderThatIsNotPairedIsNull() {
        PairedWallets.save(session, payload(accounts = listOf(account(0, "A"))), nowMs = 1L)

        assertNull(PairedWallets.get(session, UUID.randomUUID().toString()))
    }

    private fun account(index: Int, name: String, label: String? = null, group: String? = null) =
        PairFixtures.ethereumAccount(index, name, label, group)

    @Test
    fun aPairingsAddressBookIsStoredWithItsWalletAndListedWithIt() {
        PairedWallets.save(
            session,
            payload(accounts = listOf(account(0, "Savings")), addressBook = listOf(ADDRESS_BOOK_ALICE, ADDRESS_BOOK_COLD)),
            nowMs = 100L,
        )

        assertEquals(listOf(ADDRESS_BOOK_ALICE, ADDRESS_BOOK_COLD), PairedWallets.list(session).single().addressBook)
    }

    @Test
    fun repairingReplacesTheAddressBookAndDoesNotMergeIt() {
        PairedWallets.save(
            session,
            payload(accounts = listOf(account(0, "Savings")), addressBook = listOf(ADDRESS_BOOK_ALICE, ADDRESS_BOOK_COLD)),
            nowMs = 100L,
        )

        PairedWallets.save(session, payload(accounts = listOf(account(0, "Savings")), addressBook = listOf(ADDRESS_BOOK_FAUCET)), nowMs = 200L)
        assertEquals(listOf(ADDRESS_BOOK_FAUCET), PairedWallets.list(session).single().addressBook)

        PairedWallets.save(session, payload(accounts = listOf(account(0, "Savings"))), nowMs = 300L)
        assertEquals(emptyList<AddressBookEntry>(), PairedWallets.list(session).single().addressBook)
    }

    @Test
    fun eachWalletKeepsItsOwnAddressBook() {
        val otherSeed = ByteArray(16) { (it * 13 + 5).toByte() }
        PairedWallets.save(
            session,
            payload(name = "Demo", accounts = listOf(account(0, "Savings")), addressBook = listOf(ADDRESS_BOOK_ALICE)),
            nowMs = 100L,
        )
        PairedWallets.save(
            session,
            payload(name = "Other", accounts = listOf(account(0, "Savings")), seed = otherSeed, addressBook = listOf(ADDRESS_BOOK_COLD)),
            nowMs = 200L,
        )

        PairedWallets.save(
            session,
            payload(name = "Demo", accounts = listOf(account(0, "Savings")), addressBook = listOf(ADDRESS_BOOK_FAUCET)),
            nowMs = 300L,
        )

        val books = PairedWallets.list(session).associate { it.name to it.addressBook }
        assertEquals(mapOf("Demo" to listOf(ADDRESS_BOOK_FAUCET), "Other" to listOf(ADDRESS_BOOK_COLD)), books)
    }

    @Test
    fun deletingAWalletRemovesItsAddressBook() {
        val saved = PairedWallets.save(
            session,
            payload(accounts = listOf(account(0, "Savings")), addressBook = listOf(ADDRESS_BOOK_ALICE)),
            nowMs = 100L,
        )

        PairedWallets.delete(session, saved.id)

        assertEquals(emptyList<PairedWallet>(), PairedWallets.list(session))
        assertEquals(emptyMap<String, List<AddressBookEntry>>(), PairAddressBooks.load(session))
    }

    /**
     * The pairing of [seed] with its Ethereum accounts, its Bitcoin accounts when [bitcoin] is not empty, and its Address Book. Each account
     * the test names by a name other than its Auto-Name keeps that name; the Bitcoin accounts keep their Auto-Names.
     */
    private fun payload(
        name: String = "Demo",
        accounts: List<PairingAccount>,
        bitcoin: List<BtcWatchOnlyAccount> = emptyList(),
        seed: ByteArray = PairFixtures.seed,
        addressBook: List<AddressBookEntry> = emptyList(),
    ): PairingPayload {
        // Every account of a pairing is the key of its wallet, so each is derived from the seed the pairing is for.
        val ethereum = accounts.mapIndexed { index, account ->
            PairFixtures.ethereumAccount(index, account.name, account.label, account.group, seed)
        }
        val bitcoinAccounts = bitcoin.mapIndexed { index, account -> PairFixtures.bitcoinAccount(account, autoAccountName(name, index)) }
        return PairFixtures.payloadOf(ethereum + bitcoinAccounts, seed = seed, name = name, base = name).copy(addressBook = addressBook)
    }
}
