package app.lensly.app

import app.lensly.app.sign.ReviewKind
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ReviewRecord
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.vault.wipeVault
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val ACCOUNT = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb"
private const val RECIPIENT = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
private const val CONTRACT_ADDR = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"

private fun reviewOf(atMs: Long, outcome: ReviewOutcome = ReviewOutcome.SIGNED) = ReviewRecord(
    atMs = atMs,
    outcome = outcome,
    kind = ReviewKind.TRANSACTION,
    chainId = 10143L,
    networkName = "Monad Testnet",
    accountIndex = 2,
    address = ACCOUNT,
    function = "transfer",
    to = RECIPIENT,
    amount = "1.5 USDC",
    tokenContract = CONTRACT_ADDR,
    fee = listOf("Gas limit" to "21000", "Max fee" to "0.0021 MON"),
    params = listOf("to" to RECIPIENT, "value" to "1500000 (1.5 USDC)"),
    origin = "https://app.example",
    refusal = null,
    dataHash = "ab".repeat(32),
)

/** Review Records on real storage, as the sign flow writes them. The vault key is a test key, and no Keystore is involved. */
class ReviewHistoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"
    private lateinit var layout: VaultLayout
    private lateinit var session: VaultSession
    private lateinit var store: WalletStore
    private lateinit var history: ReviewHistory

    @Before
    fun openVault() {
        layout = VaultLayout(tmp.root)
        session = VaultSession(layout, ByteArray(32) { (it + 5).toByte() })
        store = WalletStore(session)
        history = ReviewHistory(session)
    }

    @After
    fun closeVault() {
        session.close()
    }

    private fun newWallet(name: String): String = store.create(
        name = name,
        deviceGenerated = false,
        words = words,
        passphrase = null,
        nowMs = 1L,
    )

    /** The one pool file that holds a Review Record of [walletId], as it is on disk. */
    private fun reviewFileOf(walletId: String): File =
        session.reviewFileForTesting(walletId, session.reviewNames(walletId).single())!!

    @Test
    fun aRecordIsListedWithEveryFactUnchanged() {
        val wallet = newWallet("MAIN")

        history.append(wallet, reviewOf(atMs = 1_000L))

        assertEquals(listOf(reviewOf(atMs = 1_000L)), history.list(wallet))
    }

    @Test
    fun theNewestRecordIsListedFirst() {
        val wallet = newWallet("MAIN")
        history.append(wallet, reviewOf(atMs = 2_000L))
        history.append(wallet, reviewOf(atMs = 3_000L))
        history.append(wallet, reviewOf(atMs = 1_000L))

        assertEquals(listOf(3_000L, 2_000L, 1_000L), history.list(wallet).map { it.atMs })
    }

    @Test
    fun aRefusalWithNoReviewKeepsItsReasonAndNoAccount() {
        val wallet = newWallet("MAIN")
        val refusal = ReviewRecord(
            atMs = 5L,
            outcome = ReviewOutcome.REFUSED,
            kind = ReviewKind.MESSAGE,
            chainId = null,
            networkName = null,
            accountIndex = null,
            address = null,
            function = null,
            to = null,
            amount = null,
            tokenContract = null,
            fee = emptyList(),
            params = emptyList(),
            origin = null,
            refusal = "The request does not say which address to sign with",
            dataHash = "cd".repeat(32),
        )

        history.append(wallet, refusal)

        assertEquals(listOf(refusal), history.list(wallet))
    }

    @Test
    fun aLongMessageAndUnicodeTextAreKept() {
        val wallet = newWallet("MAIN")
        val message = "Sign in · 署名 " + "x".repeat(70_000)
        val record = reviewOf(atMs = 7L).copy(params = listOf("message" to message), function = "Sign message 署名")

        history.append(wallet, record)

        assertEquals(listOf(record), history.list(wallet))
    }

    @Test
    fun aRecordIsEncryptedAtRest() {
        val wallet = newWallet("MAIN")
        history.append(wallet, reviewOf(atMs = 1_000L))

        val raw = reviewFileOf(wallet).readBytes()

        listOf(ACCOUNT, RECIPIENT, CONTRACT_ADDR, "transfer", "Monad Testnet", "https://app.example", "1.5 USDC").forEach { secret ->
            assertFalse(raw.containsBytes(secret.toByteArray()), "$secret can be read from the record file")
        }
    }

    @Test
    fun deletingAWalletShredsItsRecordsAndLeavesTheOthers() {
        val kept = newWallet("KEPT")
        val gone = newWallet("GONE")
        history.append(kept, reviewOf(atMs = 1L))
        history.append(gone, reviewOf(atMs = 2L))

        store.delete(gone)

        assertFalse(layout.walletDir(gone).exists())
        assertTrue(history.list(gone).isEmpty())
        assertEquals(listOf(reviewOf(atMs = 1L)), history.list(kept))
    }

    @Test
    fun aRecordSlotThatSurvivesADeleteIsUnreadable() {
        val gone = newWallet("GONE")
        history.append(gone, reviewOf(atMs = 1L))
        val name = session.reviewNames(gone).single()
        val file = reviewFileOf(gone)
        val sealed = file.readBytes()

        store.delete(gone)
        assertFalse(file.readBytes().contentEquals(sealed), "The deleted slot still holds the record")
        // A copy of the slot made before the delete is put back. Its Per-Wallet Key is gone with the wallet, so it cannot be opened.
        file.writeBytes(sealed)

        assertNull(session.getReview(gone, name))
        assertTrue(history.list(gone).isEmpty())
    }

    @Test
    fun aWipeRemovesEveryRecord() {
        val wallet = newWallet("MAIN")
        history.append(wallet, reviewOf(atMs = 1L))
        val file = reviewFileOf(wallet)
        session.close()

        wipeVault(layout) {}

        assertFalse(file.exists())
        assertFalse(layout.root.exists())
    }

    @Test
    fun aDecoyVaultSeesNoRealHistoryAndTheRealVaultNoDecoyHistory() {
        val real = newWallet("REAL")
        history.append(real, reviewOf(atMs = 1L))
        val decoy = VaultSession(layout, ByteArray(32) { 99 }, isDecoy = true)
        try {
            val decoyWallet = WalletStore(decoy).create(
                name = "Everyday",
                deviceGenerated = true,
                words = words,
                passphrase = null,
                nowMs = 2L,
            )
            ReviewHistory(decoy).append(decoyWallet, reviewOf(atMs = 2L))

            assertTrue(ReviewHistory(decoy).list(real).isEmpty())
            assertTrue(decoy.reviewNames(real).isEmpty())
            assertTrue(history.list(decoyWallet).isEmpty())
            assertEquals(listOf(reviewOf(atMs = 2L)), ReviewHistory(decoy).list(decoyWallet))
            assertEquals(listOf(reviewOf(atMs = 1L)), history.list(real))
        } finally {
            decoy.close()
        }
    }

    @Test
    fun listAllPairsEveryRecordWithItsWalletNewestFirst() {
        val first = newWallet("FIRST")
        val second = newWallet("SECOND")
        history.append(first, reviewOf(atMs = 4_000L))
        history.append(second, reviewOf(atMs = 3_000L))
        history.append(first, reviewOf(atMs = 2_000L))
        history.append(second, reviewOf(atMs = 1_000L))

        assertEquals(
            listOf(4_000L to first, 3_000L to second, 2_000L to first, 1_000L to second),
            history.listAll().map { it.record.atMs to it.walletId },
        )
    }

    @Test
    fun listAllInEitherSessionHoldsOnlyItsOwnWallets() {
        val real = newWallet("REAL")
        history.append(real, reviewOf(atMs = 1L))
        val decoy = VaultSession(layout, ByteArray(32) { 99 }, isDecoy = true)
        try {
            val decoyWallet = WalletStore(decoy).create(
                name = "Everyday",
                deviceGenerated = true,
                words = words,
                passphrase = null,
                nowMs = 2L,
            )
            ReviewHistory(decoy).append(decoyWallet, reviewOf(atMs = 2L))

            assertEquals(listOf(real), history.listAll().map { it.walletId })
            assertEquals(listOf(decoyWallet), ReviewHistory(decoy).listAll().map { it.walletId })
        } finally {
            decoy.close()
        }
    }

    @Test
    fun aDamagedRecordIsLeftOutAndTheOthersAreListed() {
        val wallet = newWallet("MAIN")
        history.append(wallet, reviewOf(atMs = 1L))
        session.putRecord(wallet, "review-damaged", byteArrayOf(9, 9, 9))

        assertEquals(listOf(reviewOf(atMs = 1L)), history.list(wallet))
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
}
