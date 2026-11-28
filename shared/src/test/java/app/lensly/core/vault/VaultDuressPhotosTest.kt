package app.lensly.core.vault

import app.lensly.ui.camera.PhotoStore
import java.io.File
import java.math.BigInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The photos the cover takes before and after a Duress Password is set stay readable by the decoy once it is promoted, and the decoy's
 * key file is shaped like the real side's at every step. On the JVM with an in-memory keystore.
 */
class VaultDuressPhotosTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"), File(tmp.root, "photos"))
        vault = Vault(layout, MemoryKeystore(), clock, KDF)
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    @Test
    fun aPlaceholderKeyIsAValidPublicKeyThatNoRandomByteStringIsAlwaysTakenFor() {
        val p = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
        repeat(500) {
            val key = placeholderPhotoKey()
            assertEquals(MediaEnvelope.KEY_BYTES, key.size)
            assertEquals(0, key[31].toInt() and 0x80, "the top bit of an X25519 public key is clear")
            assertTrue(BigInteger(1, key.reversedArray()) < p)
        }
    }

    @Test
    fun theDecoysKeyFileIsPublicKeyShapedAfterSetupRemovalAndSetupAgain() {
        val session = unlocked(APP)
        session.createWallet()
        assertPublicKeyShaped(layout.photoKeyFile(1))
        // A key file that is random bytes is caught with one in two, so the cycle is repeated.
        repeat(8) {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
            assertPublicKeyShaped(layout.photoKeyFile(1))
            runBlocking { vault.removeDuress(session) }
            assertPublicKeyShaped(layout.photoKeyFile(1))
        }
        session.close()
    }

    @Test
    fun aPhotoTakenBeforeTheDuressPasswordIsSetOpensAfterThePromotionWithTheSameBytes() {
        val session = unlocked(APP)
        session.createWallet()
        val photo = shoot()
        val sealedTwin = File(layout.photoDir(1), photo.name)
        val sizeBefore = sealedTwin.length()

        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
        session.close()
        awaitSealer()

        assertEquals(sizeBefore, sealedTwin.length(), "the twin keeps its size")
        assertEquals(0L, sealedTwin.lastModified(), "the twin keeps modification time zero")
        assertEquals(setOf(photo.name), layout.photoDir(1).list().orEmpty().toSet(), "no temp file is left")
        val promoted = unlocked(DURESS)
        runBlocking { vault.awaitCleanup() }
        val key = promoted.getVaultRecord(VaultRecordNames.PHOTO_KEY)!!
        val gallery = PhotoStore.of(layout, side = promoted.side, privateKey = key)
        assertEquals(listOf(photo.name), gallery.list().map { it.file.name })
        assertContentEquals(PHOTO, gallery.photoBytes(gallery.list().single().file))
        gallery.close()
        promoted.close()
    }

    @Test
    fun anUnlockSweepsTempFilesFromBothPhotoFoldersSoTheirNamesStayEqual() {
        val photo = shoot()
        File(layout.photoDir(1), "${photo.name}.123.tmp").writeBytes(PHOTO)
        File(layout.photoDir(0), "other.456.tmp").writeBytes(PHOTO)

        unlocked(APP).close()
        awaitSealer()

        assertEquals(setOf(photo.name), layout.photoDir(0).list().orEmpty().toSet())
        assertEquals(setOf(photo.name), layout.photoDir(1).list().orEmpty().toSet())
    }

    @Test
    fun aPhotoLeftWithoutItsTwinByAKilledCoverSaveIsShreddedAtTheNextUnlock() {
        val photo = shoot()
        File(layout.photoDir(0), "half-saved").writeBytes(PHOTO)
        File(layout.photoDir(1), "orphan").writeBytes(PHOTO)

        unlocked(APP).close()
        awaitSealer()

        assertEquals(setOf(photo.name), layout.photoDir(0).list().orEmpty().toSet())
        assertEquals(setOf(photo.name), layout.photoDir(1).list().orEmpty().toSet())
    }

    @Test
    fun aDeleteKilledBetweenItsTwoShredsLeavesNoPhotoReadableAfterTheNextUnlock() {
        val photo = shoot()
        val gallery = PhotoStore.of(layout, side = 0)
        PhotoStore.betweenDeleteStepsForTesting = { throw IllegalStateException("killed") }
        try {
            runCatching { gallery.delete(photo) }
        } finally {
            PhotoStore.betweenDeleteStepsForTesting = null
        }
        assertTrue(photo.exists(), "the photo goes after its twin, so the gallery still lists it")
        assertTrue(!File(layout.photoDir(1), photo.name).exists(), "the twin goes first")

        unlocked(APP).close()
        awaitSealer()

        assertEquals(emptySet(), layout.photoDir(0).list().orEmpty().toSet())
        assertEquals(emptySet(), layout.photoDir(1).list().orEmpty().toSet())
    }

    @Test
    fun anUnlockDoesNotWaitForASaveThatIsStillRunning() {
        val photo = shoot()
        File(layout.photoDir(1), "${photo.name}.123.tmp").writeBytes(PHOTO)
        val release = CountDownLatch(1)
        PhotoStore.sealer.execute { release.await(30, TimeUnit.SECONDS) }
        try {
            val unlock = FutureTask { unlocked(APP).close() }
            Thread(unlock).start()
            unlock.get(10, TimeUnit.SECONDS)
            assertTrue(File(layout.photoDir(1), "${photo.name}.123.tmp").exists(), "the repair is queued behind the save")
        } finally {
            release.countDown()
        }
        awaitSealer()
        assertEquals(setOf(photo.name), layout.photoDir(1).list().orEmpty().toSet())
    }

    /** Waits for the unlock's repair and for every photo copy that a Duress Password change queued. */
    private fun awaitSealer() {
        PhotoStore.whileNoSaveRuns {}
        runBlocking { vault.awaitPhotoCopies() }
    }

    @Test
    fun setupReturnsBeforeTheCopiesAreMadeAndEveryPhotoOpensAfterThePromotion() {
        val session = unlocked(APP)
        session.createWallet()
        val photos = (1..3).map { shoot(nowMs = it * 1_000L) }
        val before = photos.associate { it.name to File(layout.photoDir(1), it.name).readBytes() }
        // The sealer is held at the first copy, so no copy can be made until the test lets it go.
        val atFirstCopy = CountDownLatch(1)
        val release = CountDownLatch(1)
        resealHookForTesting = { atFirstCopy.countDown(); release.await(30, TimeUnit.SECONDS) }
        try {
            val setup = FutureTask { runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } } }
            Thread(setup).start()
            assertEquals(DuressResult.Enabled, setup.get(30, TimeUnit.SECONDS), "setup returns while the copies are still queued")
            assertTrue(atFirstCopy.await(30, TimeUnit.SECONDS))
            assertEquals(before.keys, layout.photoDir(1).list().orEmpty().toSet(), "the twins are there, with their names")
            photos.forEach { assertContentEquals(before[it.name], File(layout.photoDir(1), it.name).readBytes(), "no copy is made yet") }
        } finally {
            release.countDown()
            resealHookForTesting = null
        }
        session.close()
        awaitSealer()

        val promoted = unlocked(DURESS)
        runBlocking { vault.awaitCleanup() }
        val gallery = PhotoStore.of(layout, side = promoted.side, privateKey = promoted.getVaultRecord(VaultRecordNames.PHOTO_KEY)!!)
        assertEquals(photos.map { it.name }.toSet(), gallery.list().map { it.file.name }.toSet())
        gallery.list().forEach { assertContentEquals(PHOTO, gallery.photoBytes(it.file)) }
        gallery.close()
        promoted.close()
    }

    @Test
    fun aPhotoDeletedBeforeItsCopyIsMadeLeavesNoCopyOnTheDecoySide() {
        assertDeletedDuringSetupLeavesNoTwin(twinFirst = false)
    }

    @Test
    fun aPhotoWithoutATwinThatIsDeletedBeforeItsRandomTwinIsMadeLeavesNoCopyOnTheDecoySide() {
        assertDeletedDuringSetupLeavesNoTwin(twinFirst = true)
    }

    private fun assertDeletedDuringSetupLeavesNoTwin(twinFirst: Boolean) {
        val session = unlocked(APP)
        session.createWallet()
        val photo = shoot()
        // A photo with no side-1 twin is given a random one; otherwise its twin is sealed again.
        if (twinFirst) assertTrue(File(layout.photoDir(1), photo.name).delete())
        // The gallery's delete runs on the sealer, between two tasks; here it is the files going, as it leaves them.
        resealHookForTesting = { source ->
            File(layout.photoDir(1), source.name).delete()
            assertTrue(source.delete())
        }
        try {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
            awaitSealer()
        } finally {
            resealHookForTesting = null
        }
        session.close()

        assertEquals(emptySet(), layout.photoDir(0).list().orEmpty().toSet())
        assertEquals(emptySet(), layout.photoDir(1).list().orEmpty().toSet())
    }


    @Test
    fun aKillAfterSetupWithCopiesPendingIsFinishedAtTheNextRealUnlock() {
        val session = unlocked(APP)
        session.createWallet()
        val photos = (1..3).map { shoot(nowMs = it * 1_000L) }
        val before = photos.associate { it.name to File(layout.photoDir(1), it.name).readBytes() }

        // The process dies with the copies still in the queue: setup has returned, no twin is made, and the real vault holds the mark.
        photoCopiesKilledForTesting = true
        try {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
            awaitSealer()
        } finally {
            photoCopiesKilledForTesting = false
        }
        assertNotNull(session.photoCopiesPending(), "the real vault holds the mark")
        photos.forEach { assertContentEquals(before[it.name], File(layout.photoDir(1), it.name).readBytes(), "no copy was made before the kill") }
        val shapeWhilePending = recordFileShapes()
        session.close()

        // The next real unlock makes the copies again, then the mark goes.
        val real = unlocked(APP)
        awaitSealer()
        assertNull(real.photoCopiesPending(), "the last copy clears the mark")
        assertEquals(shapeWhilePending, recordFileShapes(), "the mark adds no record file and changes no record file's size")
        photos.forEach { assertTrue(!before[it.name]!!.contentEquals(File(layout.photoDir(1), it.name).readBytes()), "the twin was copied") }
        real.close()

        val promoted = unlocked(DURESS)
        runBlocking { vault.awaitCleanup() }
        val gallery = PhotoStore.of(layout, side = promoted.side, privateKey = promoted.photoPrivateKey())
        assertEquals(photos.map { it.name }.toSet(), gallery.list().map { it.file.name }.toSet())
        gallery.list().forEach { assertContentEquals(PHOTO, gallery.photoBytes(it.file)) }
        gallery.close()
        promoted.close()
    }

    @Test
    fun theDecoySeesNoMarkAndARemovedDuressPasswordLeavesNone() {
        val session = unlocked(APP)
        session.createWallet()
        shoot()
        photoCopiesKilledForTesting = true
        try {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
            awaitSealer()
            assertNotNull(session.photoCopiesPending())
            runBlocking { vault.removeDuress(session) }
            awaitSealer()
            assertNull(session.photoCopiesPending(), "no copy is pending for a decoy that is gone")
        } finally {
            photoCopiesKilledForTesting = false
        }
        runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } }
        awaitSealer()
        session.close()
        // A decoy that is unlocked before the real vault finishes has its own photo key record, and no mark in it.
        val promoted = unlocked(DURESS)
        assertNull(promoted.photoCopiesPending())
        assertEquals(MediaEnvelope.KEY_BYTES, promoted.photoPrivateKey()!!.size)
        promoted.close()
    }

    @Test
    fun aCameraOrGalleryTaskRunsAfterOneCopyAndNotAfterAllOfThem() {
        val session = unlocked(APP)
        session.createWallet()
        val photos = (1..6).map { shoot(nowMs = it * 1_000L) }
        val extra = shoot(nowMs = 9_000L)
        val copies = AtomicInteger()
        val copiesWhenTheDeleteRan = AtomicInteger(-1)
        val atFirstCopy = CountDownLatch(1)
        val release = CountDownLatch(1)
        resealHookForTesting = { source ->
            if (source != extra && copies.incrementAndGet() == 1) {
                atFirstCopy.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        PhotoStore.betweenDeleteStepsForTesting = { copiesWhenTheDeleteRan.set(copies.get()) }
        try {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
            assertTrue(atFirstCopy.await(30, TimeUnit.SECONDS))
            // The gallery's delete is queued behind the first copy. Without one task at a time it would wait for all of them.
            val delete = FutureTask { PhotoStore.of(layout, side = 0).delete(extra) }
            Thread(delete).start()
            Thread.sleep(300)
            release.countDown()
            assertTrue(delete.get(30, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            resealHookForTesting = null
            PhotoStore.betweenDeleteStepsForTesting = null
        }
        awaitSealer()
        session.close()

        assertTrue(copiesWhenTheDeleteRan.get() in 1 until photos.size, "the delete ran after ${copiesWhenTheDeleteRan.get()} of ${photos.size} copies")
        assertTrue(!extra.exists() && !File(layout.photoDir(1), extra.name).exists(), "the delete took both files")
    }

    @Test
    fun aSecondDuressPasswordTakesOverACopyRunThatIsStillGoing() {
        val session = unlocked(APP)
        session.createWallet()
        val photos = (1..4).map { shoot(nowMs = it * 1_000L) }
        val copies = AtomicInteger()
        val atFirstCopy = CountDownLatch(1)
        val release = CountDownLatch(1)
        resealHookForTesting = {
            if (copies.incrementAndGet() == 1) {
                atFirstCopy.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        try {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
            assertTrue(atFirstCopy.await(30, TimeUnit.SECONDS))
            val second = FutureTask { runBlocking { vault.setDuress(session, DURESS_TWO.toCharArray()) { it.createWallet() } } }
            Thread(second).start()
            Thread.sleep(500)
            release.countDown()
            assertEquals(DuressResult.Enabled, second.get(30, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            resealHookForTesting = null
        }
        awaitSealer()
        assertNull(session.photoCopiesPending(), "the second run cleared its own mark")
        session.close()

        // Only the second decoy opens the photos, all of them.
        val promoted = unlocked(DURESS_TWO)
        runBlocking { vault.awaitCleanup() }
        val gallery = PhotoStore.of(layout, side = promoted.side, privateKey = promoted.photoPrivateKey())
        assertEquals(photos.map { it.name }.toSet(), gallery.list().map { it.file.name }.toSet())
        gallery.list().forEach { assertContentEquals(PHOTO, gallery.photoBytes(it.file)) }
        gallery.close()
        promoted.close()
    }

    @Test
    fun aWipeStopsTheQueuedCopiesAtOnceAndZeroesTheirKeys() {
        val session = unlocked(APP)
        session.createWallet()
        (1..3).forEach { shoot(nowMs = it * 1_000L) }
        val keys = trackKeyCopies()
        val copies = AtomicInteger()
        val atFirstCopy = CountDownLatch(1)
        val release = CountDownLatch(1)
        resealHookForTesting = {
            if (copies.incrementAndGet() == 1) {
                atFirstCopy.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        try {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
            assertTrue(atFirstCopy.await(30, TimeUnit.SECONDS))
            vault.wipe()
        } finally {
            release.countDown()
        }
        // The hook stays in place until the sealer is idle, so it counts every copy task that runs after the wipe.
        try {
            awaitSealer()
        } finally {
            resealHookForTesting = null
            keyCopyForTesting = null
        }
        session.close()

        assertEquals(1, copies.get(), "the copies queued before the wipe are cancelled, not run")
        assertAllZero(keys)
    }

    @Test
    fun aSealerThatRefusesTheCopiesStillZeroesEveryKeyCopyOfASetup() {
        val session = unlocked(APP)
        session.createWallet()
        shoot()
        val keys = trackKeyCopies(onFirstCopy = ::refuseNextSealerTask)
        try {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
        } finally {
            keyCopyForTesting = null
        }
        awaitSealer()
        session.close()

        assertAllZero(keys)
    }

    @Test
    fun aSealerThatRefusesTheCopiesStillZeroesEveryKeyCopyOfAResume() {
        val session = unlocked(APP)
        session.createWallet()
        shoot()
        photoCopiesKilledForTesting = true
        try {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
            awaitSealer()
        } finally {
            photoCopiesKilledForTesting = false
        }
        session.close()

        val keys = trackKeyCopies(onFirstCopy = ::refuseNextSealerTask)
        try {
            unlocked(APP).close()
        } finally {
            keyCopyForTesting = null
        }
        awaitSealer()

        assertAllZero(keys)
    }

    @Test
    fun aSealerThatRefusesTheNextCopyEndsTheRunWithItsKeysZeroedAndNobodyWaitingForIt() {
        val session = unlocked(APP)
        session.createWallet()
        (1..3).forEach { shoot(nowMs = it * 1_000L) }
        val keys = trackKeyCopies()
        resealHookForTesting = { refuseNextSealerTask(fromSealerOnly = true) }
        try {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
            awaitSealer()
        } finally {
            resealHookForTesting = null
            keyCopyForTesting = null
        }
        session.close()

        assertAllZero(keys)
    }

    /** Records every key copy that a run of photo copies takes over, and calls [onFirstCopy] when the first one is made. */
    private fun trackKeyCopies(onFirstCopy: () -> Unit = {}): List<ByteArray> {
        val keys = CopyOnWriteArrayList<ByteArray>()
        keyCopyForTesting = { key ->
            if (keys.isEmpty()) onFirstCopy()
            keys += key
        }
        return keys
    }

    private fun assertAllZero(keys: List<ByteArray>) {
        assertTrue(keys.size >= 2, "the vault key copy and the photo key copy were made")
        keys.forEach { assertTrue(it.all { b -> b == 0.toByte() }, "a key copy was left in memory") }
    }

    /**
     * Makes the next task that is queued on the sealer fail as a stopped executor does, then gives the sealer back. With [fromSealerOnly]
     * the task is the next one that the sealer's own thread queues, which is the next copy of a run; the test thread's waits go through.
     */
    private fun refuseNextSealerTask(fromSealerOnly: Boolean = false) {
        val real = PhotoStore.sealer
        PhotoStore.sealer = Executor { task ->
            if (fromSealerOnly && Thread.currentThread().name != "lensly-seal") {
                real.execute(task)
            } else {
                PhotoStore.sealer = real
                throw RejectedExecutionException("refused for the test")
            }
        }
    }

    @Test
    fun aCopyThatThrowsLeavesNoTempFileAndTheOtherCopiesAreMade() {
        val session = unlocked(APP)
        session.createWallet()
        val photos = (1..3).map { shoot(nowMs = it * 1_000L) }
        // The first copy dies with a RuntimeException after its temp file exists; the others are made.
        val failed = AtomicInteger()
        copyTempHookForTesting = { if (failed.getAndIncrement() == 0) throw IllegalStateException("boom") }
        try {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
            awaitSealer()
        } finally {
            copyTempHookForTesting = null
        }
        session.close()

        assertTrue(failed.get() >= 3, "every photo was tried")
        assertEquals(photos.map { it.name }.toSet(), layout.photoDir(1).list().orEmpty().toSet(), "no temp file is left")
        assertEquals(photos.map { it.name }.toSet(), layout.photoDir(0).list().orEmpty().toSet())
    }

    @Test
    fun aRandomTwinThatThrowsLeavesNoTempFile() {
        val session = unlocked(APP)
        session.createWallet()
        val photo = shoot()
        // No twin: the copy is a random one, written to a temp file first.
        assertTrue(File(layout.photoDir(1), photo.name).delete())
        copyTempHookForTesting = { throw IllegalStateException("boom") }
        try {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
            awaitSealer()
        } finally {
            copyTempHookForTesting = null
        }
        session.close()

        assertEquals(emptySet(), layout.photoDir(1).list().orEmpty().toSet(), "no temp file is left")
    }

    @Test
    fun aWipeWhileCopiesAreQueuedLeavesNothingBehind() {
        val session = unlocked(APP)
        session.createWallet()
        (1..3).forEach { shoot(nowMs = it * 1_000L) }
        val atFirstCopy = CountDownLatch(1)
        val release = CountDownLatch(1)
        resealHookForTesting = { atFirstCopy.countDown(); release.await(30, TimeUnit.SECONDS) }
        try {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
            assertTrue(atFirstCopy.await(30, TimeUnit.SECONDS))
            session.close()
            vault.wipe()
        } finally {
            release.countDown()
            resealHookForTesting = null
        }
        awaitSealer()

        assertTrue(!layout.exists(), "the vault is gone")
        assertEquals(emptyList(), layout.root.walkTopDown().filter { it.isFile }.toList(), "no copy brought a file back")
        assertEquals(emptyList(), layout.photos.walkTopDown().filter { it.isFile }.toList(), "no copy brought a photo back")
    }

    @Test
    fun aPromotionWhileCopiesAreQueuedLosesNoPhotoAndLeavesNoTell() {
        val session = unlocked(APP)
        session.createWallet()
        val photos = (1..4).map { shoot(nowMs = it * 1_000L) }
        val names = photos.map { it.name }.toSet()
        val atFirstCopy = CountDownLatch(1)
        val release = CountDownLatch(1)
        resealHookForTesting = { atFirstCopy.countDown(); release.await(30, TimeUnit.SECONDS) }
        val promoted: VaultSession
        try {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
            assertTrue(atFirstCopy.await(30, TimeUnit.SECONDS))
            session.close()
            // The decoy is unlocked, and so promoted, while no copy but the held one is made.
            promoted = unlocked(DURESS)
        } finally {
            release.countDown()
            resealHookForTesting = null
        }
        awaitSealer()
        runBlocking { vault.awaitCleanup() }
        awaitSealer()

        val gallery = PhotoStore.of(layout, side = promoted.side, privateKey = promoted.photoPrivateKey())
        assertEquals(names, gallery.list().map { it.file.name }.toSet(), "every photo is in the promoted gallery, none twice")
        gallery.list().forEach { assertContentEquals(PHOTO, gallery.photoBytes(it.file), "${it.file.name} has its own bytes") }
        gallery.close()
        promoted.close()
        for (side in 0..1) {
            assertEquals(names, layout.photoDir(side).list().orEmpty().toSet(), "side $side holds the photos' names and no temp file")
            layout.photoDir(side).listFiles().orEmpty().forEach { assertEquals(0L, it.lastModified(), "${it.name} has modification time zero") }
        }
        assertEquals(
            layout.photoDir(0).listFiles().orEmpty().map { it.length() }.sorted(),
            layout.photoDir(1).listFiles().orEmpty().map { it.length() }.sorted(),
            "the two folders hold files of equal sizes",
        )
    }

    /** The name and size of every file of the folder of vault records, which a mark must not change. */
    private fun recordFileShapes() = layout.vaultRecords.listFiles().orEmpty().map { it.name to it.length() }.sortedBy { it.first }

    private fun shoot(nowMs: Long = 1_000L): File {
        val known = layout.photoDir(0).list().orEmpty().toSet()
        // The cover saves on the sealer, one file after the other, so the unlock's repair of the photo folders, which shreds a photo whose
        // twin is not there yet, cannot run between the two files of a save.
        assertTrue(PhotoStore.whileNoSaveRuns { PhotoStore.forCover(layout).savePhoto(PHOTO.copyOf(), nowMs = nowMs) })
        return layout.photoDir(0).listFiles().orEmpty().single { it.name !in known }
    }

    private fun unlocked(password: String): VaultSession {
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        return (runBlocking { vault.unlock(password.toCharArray()) } as UnlockResult.Unlocked).session
    }

    private fun assertPublicKeyShaped(file: File) {
        val bytes = file.readBytes()
        assertEquals(MediaEnvelope.KEY_BYTES, bytes.size)
        assertEquals(0, bytes[31].toInt() and 0x80, "${file.name} holds a byte string that no public key is")
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        const val DURESS_TWO = "Mn4&Bv7*Cx1\$Zq9^Lk2Jd"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
        val PHOTO = ByteArray(300) { (it * 7).toByte() }
    }
}
