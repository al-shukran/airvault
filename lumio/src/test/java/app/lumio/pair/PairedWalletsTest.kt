package app.lumio.pair

import app.lensly.app.export.PairingAccount
import app.lensly.app.export.PairingApp
import app.lensly.app.export.PairingKey
import app.lensly.app.export.PairingPayload
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.btc.ScriptType
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.toHex
import org.junit.Assert.assertEquals
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
            listOf(PairedAccount(0, "Savings", "cold", "Long term"), PairedAccount(1, "Trading", null, "Hot")),
            listed.evmAccounts,
        )
        assertEquals(setOf(PairFamily.EVM), listed.families)
        assertEquals(HdWallet(PairFixtures.seed).accountXpub().publicKey.toHex(), requireNotNull(listed.evm).publicKey.toHex())
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

        assertEquals(listOf(PairedAccount(0, "Cold store", null, null)), PairedWallets.list(session).single().evmAccounts)
    }

    @Test
    fun labelsAndGroupsAreReplacedNotKeptOnRepair() {
        PairedWallets.save(session, payload(accounts = listOf(account(0, "Savings", "cold", "Long term"))), nowMs = 100L)

        PairedWallets.save(session, payload(accounts = listOf(account(0, "Savings"))), nowMs = 200L)

        assertEquals(listOf(PairedAccount(0, "Savings", null, null)), PairedWallets.list(session).single().evmAccounts)
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
        assertEquals(listOf(PairedAccount(1, "B", null, null)), listed.evmAccounts.filter { it.index == 1 })
    }

    @Test
    fun anAccountLensllyAddedIsAddedOnRepair() {
        PairedWallets.save(session, payload(accounts = listOf(account(0, "A"))), nowMs = 100L)

        PairedWallets.save(session, payload(accounts = listOf(account(0, "A"), account(1, "B"), account(2, "C", "x", null))), nowMs = 200L)

        assertEquals(
            listOf(PairedAccount(0, "A", null, null), PairedAccount(1, "B", null, null), PairedAccount(2, "C", "x", null)),
            PairedWallets.list(session).single().evmAccounts,
        )
    }

    @Test
    fun bitcoinForTheSameSeedJoinsTheWalletAndKeepsTheEthereumKey() {
        PairedWallets.save(session, payload(accounts = listOf(account(0, "A"))), nowMs = 100L)

        PairedWallets.save(session, payload(accounts = listOf(account(0, "A")), bitcoin = PairFixtures.allAccounts(PairFixtures.seed)), nowMs = 200L)

        val listed = PairedWallets.list(session).single()
        assertEquals(setOf(PairFamily.EVM, PairFamily.BITCOIN), listed.families)
        assertEquals(8, listed.bitcoin.size)
        assertEquals(HdWallet(PairFixtures.seed).accountXpub().publicKey.toHex(), requireNotNull(listed.evm).publicKey.toHex())
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
        assertEquals(setOf(PairFamily.EVM), listed.families)
    }

    @Test
    fun aStoredBitcoinPairingReadsBackExactly() {
        val accounts = listOf(PairFixtures.account(PairFixtures.seed, BtcNetwork.TESTNET4, ScriptType.TAPROOT))
        PairedWallets.save(session, payload(accounts = listOf(account(0, "A")), bitcoin = accounts), nowMs = 1L)

        val read = PairedWallets.list(session).single().bitcoin.single()

        assertEquals(accounts.single().descriptor, read.descriptor)
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

    private fun account(index: Int, name: String?, label: String? = null, group: String? = null) =
        PairingAccount(key = 0, child = index, name = name, label = label, group = group)

    /** The pairing of [seed] with its Ethereum accounts, and its Bitcoin accounts when [bitcoin] is not empty. */
    private fun payload(
        name: String = "Demo",
        accounts: List<PairingAccount>,
        bitcoin: List<BtcWatchOnlyAccount> = emptyList(),
        seed: ByteArray = PairFixtures.seed,
    ): PairingPayload {
        val wallet = HdWallet(seed)
        val apps = mutableListOf(
            PairingApp(app = "ethereum", keys = listOf(PairingKey.ethereumAccount(wallet.accountXpub())), accounts = accounts),
        )
        if (bitcoin.isNotEmpty()) {
            apps += PairingApp(
                app = "bitcoin",
                keys = bitcoin.map { PairingKey.bitcoinAccount(it) },
                accounts = bitcoin.indices.map { PairingAccount(key = it, child = null, name = null, label = null, group = null) },
            )
        }
        return PairingPayload(fingerprint = wallet.masterFingerprint.toLong() and 0xFFFFFFFFL, name = name, apps = apps)
    }
}
