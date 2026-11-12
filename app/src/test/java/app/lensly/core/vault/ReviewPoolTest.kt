package app.lensly.core.vault

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Review Record pool: the shape it shows, the batches it grows by, the keys that guard it, the migration of records from
 * wallet folders, and the overwrite on delete.
 */
class ReviewPoolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"))
        vault = Vault(layout, keys, clock, KDF)
    }

    @Test
    fun thePoolHasTheSameFilesAndSizesWithZeroOneOrSixtyThreeRecords() {
        val expected = List(ReviewPoolFormat.BATCH) { ReviewPoolFormat.FILE_BYTES.toLong() }
        listOf(0, 1, 63).forEach { count ->
            assertEquals(expected, poolSizesOf(populatedVault("count-$count", count)), "with $count records")
        }
    }

    @Test
    fun thePoolGrowsOnlyInSixtyFourFileBatches() {
        val afterSixtyFour = poolSizesOf(populatedVault("grow-64", 64))

        assertEquals(2 * ReviewPoolFormat.BATCH, afterSixtyFour.size)
        assertTrue(afterSixtyFour.all { it == ReviewPoolFormat.FILE_BYTES.toLong() })
    }

    @Test
    fun aDecoyVaultCannotReadTheRealIndexOrSeeItsRecords() {
        create()
        val real = unlock()
        val realWallet = real.createWallet()
        real.putReview(realWallet, "review-real", byteArrayOf(1, 2, 3))
        val realPage = ReviewPool(layout.reviewPool, real.copyOfVaultKey()).pageFile(0)
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(real, DURESS.toCharArray()) { it.createWallet() } })
        real.close()

        val decoy = unlock(DURESS)
        assertTrue(decoy.isDecoy)
        assertEquals(emptyList(), decoy.reviewNames(realWallet))
        assertFailsWith<VaultException> { ReviewPool(layout.reviewPool, decoy.copyOfVaultKey()).decodePage(realPage, 0) }
        decoy.close()
    }

    @Test
    fun aDecoyVaultCannotReadTheRealFreeSlotList() {
        create()
        val real = unlock()
        real.createWallet()
        val realFree = WalletPad(layout, real.copyOfVaultKey(), REAL_SECTION).freeSlotsForTesting()
        assertEquals(WALLET_SLOT_STEP - 1, realFree.size)
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(real, DURESS.toCharArray()) { it.createWallet() } })
        real.close()

        val decoy = unlock(DURESS)
        val decoyKey = decoy.copyOfVaultKey()
        // The decoy's key opens its own section of the pad, and none of the real vault's.
        assertEquals(emptySet(), WalletPad(layout, decoyKey, REAL_SECTION).freeSlotsForTesting())
        assertEquals(emptySet(), WalletPad(layout, ByteArray(32) { 9 }, REAL_SECTION).freeSlotsForTesting())
        assertTrue(WalletPad(layout, decoyKey, DECOY_SECTION).freeSlotsForTesting().isNotEmpty())
        decoy.close()
    }

    @Test
    fun aRecordInAWalletFolderMovesIntoThePoolOnTheNextUnlockAndTheMoveRepeatsSafely() {
        create()
        val session = unlock()
        val wallet = session.createWallet()
        val bytes = "a review written by an earlier build".toByteArray()
        session.putRecord(wallet, "review-old", bytes)
        assertEquals(listOf("review-old"), session.recordNames(wallet, "review-"))
        session.close()

        val reopened = unlock()

        assertContentEquals(bytes, reopened.getReview(wallet, "review-old"))
        assertEquals(emptyList(), reopened.recordNames(wallet, "review-"))
        assertFalse(File(layout.walletDir(wallet), "review-old.bin").exists())
        reopened.migrateLegacyReviews()
        assertEquals(listOf("review-old"), reopened.reviewNames(wallet))
        reopened.close()
    }

    @Test
    fun deletingAWalletOverwritesItsPoolSlots() {
        create()
        val session = unlock()
        val wallet = session.createWallet()
        session.putReview(wallet, "review-gone", ByteArray(100) { 7 })
        val file = session.reviewFileForTesting(wallet, "review-gone")!!
        val before = file.readBytes()

        session.deleteWallet(wallet)

        val after = file.readBytes()
        assertEquals(before.size, after.size)
        assertFalse(before.contentEquals(after), "The deleted slot still holds its record")
        assertNull(session.getReview(wallet, "review-gone"))
        session.close()
    }

    @Test
    fun wipingThePoolOverwritesEveryFileBeforeItsDeletion() {
        create()
        val session = unlock()
        val wallet = session.createWallet()
        session.putReview(wallet, "review-kept-until-wipe", ByteArray(64) { 9 })
        val file = session.reviewFileForTesting(wallet, "review-kept-until-wipe")!!
        val before = file.readBytes()
        session.close()

        ReviewPool.shredAll(layout.reviewPool)

        val after = file.readBytes()
        assertEquals(before.size, after.size)
        assertFalse(before.contentEquals(after))
    }

    @Test
    fun aRecordLargerThanOneSlotIsRefused() {
        create()
        val session = unlock()
        val wallet = session.createWallet()

        assertFailsWith<IllegalArgumentException> {
            session.putReview(wallet, "review-big", ByteArray(ReviewPoolFormat.RECORD_BYTES + 1))
        }
        session.close()
    }

    /** Creates a vault in its own folder with [count] Review Records on one wallet, and returns the folder. */
    private fun populatedVault(name: String, count: Int): File {
        val root = File(tmp.root, name)
        val other = Vault(VaultLayout(root), MemoryKeystore(), clock, KDF)
        assertEquals(CreateResult.Created, runBlocking { other.create(APP.toCharArray()) })
        val session = (runBlocking { other.unlock(APP.toCharArray()) } as UnlockResult.Unlocked).session
        val wallet = session.createWallet()
        repeat(count) { session.putReview(wallet, "review-$it", byteArrayOf(it.toByte(), 1, 2, 3)) }
        session.close()
        return root
    }

    private fun poolSizesOf(root: File): List<Long> =
        File(root, "review-pool").listFiles().orEmpty().filter { it.isFile }.map { it.length() }.sorted()

    private fun create() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    private fun unlock(password: String = APP): VaultSession {
        val result = runBlocking { vault.unlock(password.toCharArray()) }
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return result.session
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
