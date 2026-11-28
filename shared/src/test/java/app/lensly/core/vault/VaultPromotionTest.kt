package app.lensly.core.vault

import app.lensly.ui.camera.PhotoStore
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unlocking with the Duress Password erases the real vault and promotes the decoy to be the vault. On the JVM with an in-memory keystore.
 * Each attempt moves the clock past any failure delay first.
 */
class VaultPromotionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        layout = layoutAt("vault")
        vault = Vault(layout, keys, clock, KDF)
    }

    @Test
    fun aDuressUnlockErasesTheRealVaultAndPromotesTheDecoy() {
        create(P1)
        val real = unlocked(P1)
        val realWallet = real.createWallet()
        real.putRecord(realWallet, "secret", byteArrayOf(1, 2, 3))
        real.putVaultRecord("settings", byteArrayOf(4))
        val realKey = real.copyOfVaultKey()
        val decoy = setDuress(real, P2)
        real.close()

        val promoted = unlocked(P2)
        awaitCleanup()

        // "2" opens the decoy's wallet as the vault, and nothing of the real vault is in it.
        assertEquals(1, promoted.side)
        assertEquals(decoy.wallets, promoted.listWallets())
        assertNull(promoted.getVaultRecord("settings"))
        // The old vault key opens nothing any more: its wallet is an unused slot, its records are spares.
        assertFalse(ownsWallet(keys, layout, realKey, realWallet))
        assertTrue(WalletFolder.isUnused(layout.walletDir(realWallet)))
        VaultRecordNames.KNOWN.forEach { assertFalse(File(layout.vaultRecords, VaultRecordNames.fileName(realKey, it)).exists()) }
        // "1" opens nothing.
        assertRejected(attempt(P1))
        // No Duress Password exists: it opens nothing, and setting one makes an ordinary decoy.
        assertRejected(attempt(P3))
        promoted.close()
        val again = unlocked(P2)
        assertEquals(decoy.wallets, again.listWallets())
        val next = setDuress(again, P3)
        again.close()
        // The new vault's Duress Password is refused where it is only checked, as the first one was, and nothing changes.
        val keyFile = layout.vaultKey.readBytes()
        assertRejected(verify(P3))
        assertContentEquals(keyFile, layout.vaultKey.readBytes())
        assertFalse(layout.promotionMarker.exists())
        val reopened = unlocked(P2)
        assertEquals(decoy.wallets, reopened.listWallets())
        reopened.close()
        val second = unlocked(P3)
        assertEquals(next.wallets, second.listWallets())
        assertEquals(0, second.side)
        second.close()
        assertRejected(attempt(P2))
        realKey.fill(0)
        decoy.key.fill(0)
        next.key.fill(0)
    }

    @Test
    fun aChainOfPromotionsKeepsOnlyTheNewestVaultAndNothingAccumulates() {
        create(P[0])
        var session = unlocked(P[0])
        session.createWallet()
        savePhotos(layout, "before")
        val gone = mutableListOf<Pair<ByteArray, List<String>>>()

        for (generation in 0 until 4) {
            gone += session.copyOfVaultKey() to session.listWallets()
            val next = setDuress(session, P[generation + 1])
            // The cover shoots into both folders.
            savePhotos(layout, "g$generation")
            session.close()
            session = unlocked(P[generation + 1])
            awaitCleanup()

            assertEquals((generation + 1) % 2, session.side, "the vault keeps the side the decoy had")
            assertEquals(next.wallets, session.listWallets())
            next.key.fill(0)
            // Every older password opens nothing, and the newest one still does.
            for (older in 0..generation) assertRejected(attempt(P[older]))
            session.close()
            session = unlocked(P[generation + 1])
            assertSameShape(freshShape("before", *Array(generation + 1) { "g$it" }), shape(layout, keys), "after promotion ${generation + 1} the files are those of a vault that never had a Duress Password")
        }

        gone.forEach { (key, wallets) ->
            wallets.forEach { assertFalse(ownsWallet(keys, layout, key, it)) }
            VaultRecordNames.KNOWN.forEach { assertFalse(File(layout.vaultRecords, VaultRecordNames.fileName(key, it)).exists()) }
            assertEquals(0, ReviewPool(layout.reviewPool, key).use { pool -> pool.batches() })
            key.fill(0)
        }
        session.close()
        assertRejected(attempt(P[3]))
        assertEquals(0, unlocked(P[4]).also { it.close() }.side)
    }

    @Test
    fun aCrashRightAfterTheCommitIsFinishedByTheNextUnlock() {
        val fresh = freshShape("before", "after")
        create(P1)
        val real = unlocked(P1)
        val realWallet = real.createWallet()
        savePhotos(layout, "before")
        val realKey = real.copyOfVaultKey()
        val decoy = setDuress(real, P2)
        savePhotos(layout, "after")
        real.close()

        vault.skipCleanupForTesting = true
        unlocked(P2).close()
        // The commit is in place and the cleanup never ran: the marker names the key file, and the old wallet's folder is still there.
        assertEquals(VaultKeyFile.digestOf(layout.vaultKey.readBytes()), String(layout.promotionMarker.readBytes(), Charsets.US_ASCII))
        assertTrue(layout.walletKeyFile(realWallet).isFile)
        assertRejected(attempt(P1))

        // A new process: the next unlock finishes the cleanup.
        vault = Vault(layout, keys, clock, KDF)
        val promoted = unlocked(P2)
        awaitCleanup()

        assertFalse(layout.promotionMarker.exists())
        assertEquals(decoy.wallets, promoted.listWallets())
        assertFalse(ownsWallet(keys, layout, realKey, realWallet))
        assertTrue(WalletFolder.isUnused(layout.walletDir(realWallet)))
        assertSameShape(fresh, shape(layout, keys))
        promoted.close()
        realKey.fill(0)
        decoy.key.fill(0)
    }

    @Test
    fun aMarkerThatNamesAnotherFileIsRemovedAndNothingChanges() {
        create(P1)
        val real = unlocked(P1)
        val wallet = real.createWallet()
        setDuress(real, P2).key.fill(0)
        real.close()
        layout.promotionMarker.writeBytes(VaultKeyFile.digestOf(ByteArray(4)).toByteArray(Charsets.US_ASCII))
        val keyFile = layout.vaultKey.readBytes()

        vault = Vault(layout, keys, clock, KDF)
        val session = unlocked(P1)
        awaitCleanup()

        assertFalse(layout.promotionMarker.exists())
        assertContentEquals(keyFile, layout.vaultKey.readBytes())
        assertEquals(listOf(wallet), session.listWallets())
        session.close()
        // The Duress Password still promotes the decoy, so the marker took nothing from it.
        assertEquals(1, unlocked(P2).also { it.close() }.side)
    }

    @Test
    fun aCrashAtTheCommitOfAMiddleGenerationIsFinishedAndTheChainGoesOn() {
        val fresh = freshShape("before", "g0", "g1", "g2", "g3")
        create(P[0])
        var session = unlocked(P[0])
        session.createWallet()
        savePhotos(layout, "before")
        val keysSeen = mutableListOf<ByteArray>()
        for (generation in 0 until 4) {
            keysSeen += session.copyOfVaultKey()
            val next = setDuress(session, P[generation + 1])
            savePhotos(layout, "g$generation")
            next.key.fill(0)
            session.close()
            // The second promotion dies right after its commit.
            vault.skipCleanupForTesting = generation == 1
            session = unlocked(P[generation + 1])
            if (generation == 1) {
                assertTrue(layout.promotionMarker.exists())
                session.close()
                vault = Vault(layout, keys, clock, KDF)
                session = unlocked(P[generation + 1])
            }
            awaitCleanup()
            assertFalse(layout.promotionMarker.exists())
        }

        for (older in 0 until 4) assertRejected(attempt(P[older]))
        assertSameShape(fresh, shape(layout, keys))
        keysSeen.forEach { key ->
            assertTrue(layout.wallets.list().orEmpty().none { ownsWallet(keys, layout, key, it) })
            key.fill(0)
        }
        session.close()
        unlocked(P[4]).close()
    }

    @Test
    fun aSetupRightAfterAPromotionFinishesThePendingCleanupFirst() {
        create(P1)
        val real = unlocked(P1)
        val realWallet = real.createWallet()
        val realKey = real.copyOfVaultKey()
        setDuress(real, P2).key.fill(0)
        real.close()
        vault.skipCleanupForTesting = true
        val promoted = unlocked(P2)

        val next = setDuress(promoted, P3)
        promoted.close()

        // The old vault is gone, and the new decoy was not taken for part of it.
        assertFalse(layout.promotionMarker.exists())
        assertFalse(ownsWallet(keys, layout, realKey, realWallet))
        assertEquals(next.wallets, walletsOf(P3))
        realKey.fill(0)
        next.key.fill(0)
    }

    @Test
    fun aPhotoOnlyTheDecoyHadDeletedLeavesNoUnreadableTwinBehind() {
        create(P1)
        val real = unlocked(P1)
        setDuress(real, P2).key.fill(0)
        real.close()
        val kept = File(layout.photoDir(0), "kept").apply { writeBytes(ByteArray(8)) }
        val keptTwin = File(layout.photoDir(1), "kept").apply { writeBytes(ByteArray(8)) }
        val orphan = File(layout.photoDir(0), "orphan").apply { writeBytes(ByteArray(8)) }

        val promoted = unlocked(P2)
        awaitCleanup()
        promoted.close()

        assertTrue(kept.exists() && keptTwin.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun aFailedCommitRefusesTheUnlockAndChangesNothing() {
        var armed = false
        var saves = 0
        val failing = object : KeystoreBackend by keys {
            override fun createFailureMacKey(seq: Long): SecretKey {
                // The first save is the counted try. The second is the commit's, which fails.
                if (armed && ++saves >= 2) throw VaultException("The Keystore refused the key.")
                return keys.createFailureMacKey(seq)
            }
        }
        vault = Vault(layout, failing, clock, KDF)
        create(P1)
        val real = unlocked(P1)
        val wallet = real.createWallet()
        val decoy = setDuress(real, P2)
        real.close()
        val keyFile = layout.vaultKey.readBytes()

        armed = true
        assertRejected(attempt(P2))
        armed = false

        assertContentEquals(keyFile, layout.vaultKey.readBytes())
        assertFalse(layout.promotionMarker.exists())
        val reopened = unlocked(P1)
        assertEquals(listOf(wallet), reopened.listWallets())
        reopened.close()
        // The decoy is still there to be promoted.
        assertEquals(decoy.wallets, walletsOf(P2))
        decoy.key.fill(0)
    }

    @Test
    fun aMarkerThatCannotBeWrittenRefusesTheUnlockAndChangesNothing() {
        create(P1)
        val real = unlocked(P1)
        val wallet = real.createWallet()
        setDuress(real, P2).key.fill(0)
        real.close()
        val keyFile = layout.vaultKey.readBytes()
        // A folder with a file in it stands where the marker goes, so the marker cannot be written.
        File(layout.promotionMarker, "in-the-way").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1))

        assertRejected(attempt(P2))

        assertContentEquals(keyFile, layout.vaultKey.readBytes())
        layout.promotionMarker.deleteRecursively()
        val reopened = unlocked(P1)
        assertEquals(listOf(wallet), reopened.listWallets())
        reopened.close()
    }

    @Test
    fun theDuressPasswordPromotesNothingWhereItIsOnlyChecked() {
        create(P1)
        val real = unlocked(P1)
        val wallet = real.createWallet()
        setDuress(real, P2).key.fill(0)
        real.close()
        val keyFile = layout.vaultKey.readBytes()

        // Neither the check nor the failure limits take it as the vault's password, and neither erases the real vault.
        assertRejected(verify(P2))
        assertEquals(FailureCapResult.Rejected, runBlocking { vault.setFailureLimits(P2.toCharArray(), 30_000L) })
        clock.advance(FailureDelay.MAX_CAP_MS + 1)

        assertContentEquals(keyFile, layout.vaultKey.readBytes())
        assertFalse(layout.promotionMarker.exists())
        val reopened = unlocked(P1)
        assertEquals(listOf(wallet), reopened.listWallets())
        assertEquals(0, reopened.side)
        reopened.close()
        // The App Password is checked as before.
        val checked = verify(P1)
        assertTrue(checked is UnlockResult.Unlocked)
        (checked as UnlockResult.Unlocked).session.close()
    }

    @Test
    fun aVerifyThatIsTheLastAllowedWrongTryErasesLikeAnyWrongPassword() {
        create(P1)
        val real = unlocked(P1)
        real.createWallet()
        setDuress(real, P2).key.fill(0)
        real.close()
        assertEquals(FailureCapResult.Saved, runBlocking { vault.setFailureLimits(P1.toCharArray(), null, wipeAfter = FailureDelay.MIN_WIPE_AFTER) })

        repeat(FailureDelay.MIN_WIPE_AFTER - 1) { assertRejected(verify(P2)) }
        assertEquals(UnlockResult.Erased, verify(P2))
        assertFalse(vault.exists())
    }

    @Test
    fun theSealedKeyOfBiometricUnlockAndItsKeystoreKeyAreGoneAtTheCommitEvenWhenTheCleanupNeverRuns() {
        create(P1)
        val real = unlocked(P1)
        setDuress(real, P2).key.fill(0)
        real.close()
        val sealedKey = File(layout.root, BiometricUnlock.BLOB_FILE_NAME).apply { writeBytes(ByteArray(64) { 7 }) }
        assertFalse(keys.biometricKeyDeleted)

        vault.skipCleanupForTesting = true
        unlocked(P2).close()

        assertTrue(layout.promotionMarker.exists(), "the cleanup did not run")
        assertFalse(sealedKey.exists(), "the sealed Vault Key of the old vault is gone with the commit")
        assertTrue(keys.biometricKeyDeleted, "its Keystore key is gone with the commit")
    }

    @Test
    fun theCleanupKeepsASealedKeyThatWasMadeAfterTheCommitAndDropsOneThatWasLeftBefore() {
        create(P1)
        val real = unlocked(P1)
        setDuress(real, P2).key.fill(0)
        real.close()
        vault.skipCleanupForTesting = true
        unlocked(P2).close()
        val sealedKey = File(layout.root, BiometricUnlock.BLOB_FILE_NAME)
        val promotedAt = layout.promotionMarker.lastModified()

        // Biometric unlock turned on after the commit: newer than the marker.
        sealedKey.writeBytes(ByteArray(64) { 7 })
        sealedKey.setLastModified(promotedAt + 60_000L)
        FailureStore.forget(layout.root)
        vault = Vault(layout, keys, clock, KDF)
        unlocked(P2).also { awaitCleanup() }.close()
        assertFalse(layout.promotionMarker.exists())
        assertTrue(sealedKey.exists(), "a sealed key that the owner made after the promotion is not the old vault's")

        // The old vault's sealed key, left by a drop that failed: older than the marker.
        layout.promotionMarker.writeBytes(VaultKeyFile.digestOf(layout.vaultKey.readBytes()).toByteArray(Charsets.US_ASCII))
        sealedKey.setLastModified(layout.promotionMarker.lastModified() - 60_000L)
        FailureStore.forget(layout.root)
        vault = Vault(layout, keys, clock, KDF)
        unlocked(P2).also { awaitCleanup() }.close()
        assertFalse(sealedKey.exists(), "the backstop drops the old vault's sealed key")
    }

    @Test
    fun aDuressUnlockThatIsOnlyCheckedStartsNoCleanup() {
        create(P1)
        val real = unlocked(P1)
        val realWallet = real.createWallet()
        setDuress(real, P2).key.fill(0)
        real.close()
        vault.skipCleanupForTesting = true
        unlocked(P2).close()
        // A new process, and the App Password of the promoted vault is only checked.
        FailureStore.forget(layout.root)
        vault = Vault(layout, keys, clock, KDF)
        val checked = verify(P2)
        awaitCleanup()
        (checked as UnlockResult.Unlocked).session.close()

        assertTrue(layout.promotionMarker.exists(), "a password check leaves the cleanup to the next unlock")
        assertTrue(layout.walletKeyFile(realWallet).isFile)

        val promoted = unlocked(P2)
        awaitCleanup()
        promoted.close()
        assertFalse(layout.promotionMarker.exists())
        assertTrue(WalletFolder.isUnused(layout.walletDir(realWallet)))
    }

    @Test
    fun theOldUnlockFileIsOverwrittenByTheCommitItselfNotByTheCleanupThatFollows() {
        create(P1)
        val real = unlocked(P1)
        setDuress(real, P2).key.fill(0)
        real.close()
        val oldBytes = layout.vaultKey.readBytes()
        // Another name for the old file's bytes, as a snapshot of the folder would hold: the overwrite is in place, so it reaches that name too.
        val spy = File(tmp.root, "spy")
        Files.createLink(spy.toPath(), layout.vaultKey.toPath())

        vault.skipCleanupForTesting = true
        unlocked(P2).close()

        // The cleanup never ran, so a kill or a lock right after the commit finds nothing of the old file.
        assertTrue(layout.promotionMarker.exists())
        assertEquals(oldBytes.size, spy.length().toInt())
        assertFalse(spy.readBytes().contentEquals(oldBytes), "the old unlock.bin was overwritten, not only unlinked")
    }

    @Test
    fun theTwinlessPhotosAreShreddedOffTheSealerThread() {
        create(P1)
        savePhotos(layout, "kept")
        File(layout.photoDir(1), "twinless").writeBytes(ByteArray(10))
        val threads = mutableListOf<String>()

        shredTwinlessPhotos(layout, side = 0) { file ->
            threads += Thread.currentThread().name
            file.delete()
        }

        assertEquals(1, threads.size)
        assertFalse(threads.single() == "lensly-seal", "the shredding must not hold up the thread that seals photos")
        assertFalse(File(layout.photoDir(1), "twinless").exists())
        assertTrue(File(layout.photoDir(1), "kept-a").exists(), "a photo with a twin stays")
    }

    @Test
    fun aKillBetweenTheMarkerAndTheNewUnlockFileLeavesTheOldVaultAndTheNextUnlockRemovesTheMarker() {
        var armed = false
        var saves = 0
        val dying = object : KeystoreBackend by keys {
            override fun createFailureMacKey(seq: Long): SecretKey {
                // The first save is the counted try. The second is the commit's, which the process does not survive.
                if (armed && ++saves >= 2) throw Killed()
                return keys.createFailureMacKey(seq)
            }
        }
        vault = Vault(layout, dying, clock, KDF)
        create(P1)
        val real = unlocked(P1)
        val wallet = real.createWallet()
        val decoy = setDuress(real, P2)
        real.close()
        val keyFile = layout.vaultKey.readBytes()

        armed = true
        assertFailsWith<Killed> { attempt(P2) }

        // The marker and the new file are on disk, and the old file is still the vault's.
        assertTrue(layout.promotionMarker.exists())
        assertEquals(1, keyFileTemps().size)
        assertContentEquals(keyFile, layout.vaultKey.readBytes())
        FailureStore.forget(layout.root)
        vault = Vault(layout, keys, clock, KDF)
        val reopened = unlocked(P1)
        awaitCleanup()

        assertEquals(listOf(wallet), reopened.listWallets())
        assertFalse(layout.promotionMarker.exists())
        assertEquals(emptyList(), keyFileTemps(), "the new file of a commit that never happened is removed")
        assertContentEquals(keyFile, layout.vaultKey.readBytes(), "the live file was never overwritten")
        reopened.close()
        // The Duress Password still promotes the decoy.
        assertEquals(decoy.wallets, walletsOf(P2))
        decoy.key.fill(0)
    }

    @Test
    fun aKillAfterTheStateNamesTheNewUnlockFileAndBeforeTheOldOneIsOverwrittenIsRolledForwardByTheNextUnlock() {
        var armed = false
        var saves = 0
        val dying = object : KeystoreBackend by keys {
            override fun createFailureMacKey(seq: Long): SecretKey {
                if (armed) saves++
                return keys.createFailureMacKey(seq)
            }

            override fun deleteFailureMacKeysExcept(keepSeq: Long?) {
                // After the commit's save has written both copies of the state, and before anything is overwritten or replaced.
                if (armed && saves >= 2) throw Killed()
                keys.deleteFailureMacKeysExcept(keepSeq)
            }
        }
        vault = Vault(layout, dying, clock, KDF)
        val (oldBytes, decoy) = setUpForPromotion()

        armed = true
        assertFailsWith<Killed> { attempt(P2) }
        armed = false
        assertTrue(layout.promotionMarker.exists())
        assertContentEquals(oldBytes, layout.vaultKey.readBytes(), "nothing was overwritten yet")

        assertPromotedAfterReopen(oldBytes, decoy)
    }

    @Test
    fun aKillAfterTheOldUnlockFileIsOverwrittenAndBeforeTheReplaceIsRolledForwardAndTheOldBytesAreNowhere() {
        val (oldBytes, decoy) = setUpForPromotion()
        vault.overwriteKeyFile = { file ->
            overwriteInPlace(file)
            throw Killed()
        }

        assertFailsWith<Killed> { attempt(P2) }

        // The old file was overwritten before the new one replaced it, so a kill at this point leaves the old bytes nowhere.
        assertEquals(1, keyFileTemps().size)
        assertEquals(emptyList(), filesHolding(oldBytes))
        assertPromotedAfterReopen(oldBytes, decoy)
    }

    @Test
    fun aKillBeforeTheOldUnlockFileIsOverwrittenIsRolledForwardAndTheRollForwardOverwritesIt() {
        val (oldBytes, decoy) = setUpForPromotion()
        vault.overwriteKeyFile = { throw Killed() }

        assertFailsWith<Killed> { attempt(P2) }

        assertContentEquals(oldBytes, layout.vaultKey.readBytes())
        // A descriptor on the old file sees the bytes change only if the roll forward overwrites it in place before the replace.
        RandomAccessFile(layout.vaultKey, "r").use { held ->
            var overwrites = 0
            assertPromotedAfterReopen(oldBytes, decoy) { reopened ->
                reopened.overwriteKeyFile = { file ->
                    overwrites++
                    overwriteInPlace(file)
                }
            }
            val seen = ByteArray(oldBytes.size)
            held.seek(0)
            held.readFully(seen)
            assertEquals(1, overwrites, "the roll forward overwrites the old unlock.bin once")
            assertFalse(oldBytes.contentEquals(seen), "the old file was overwritten in place before it was replaced")
        }
    }

    @Test
    fun thePromotionFolderIsSyncedAfterTheStateNamesTheNewFileAndBeforeTheOldFileIsOverwritten() {
        val (_, decoy) = setUpForPromotion()
        val events = mutableListOf<String>()
        val recording = object : KeystoreBackend by keys {
            override fun deleteFailureMacKeysExcept(keepSeq: Long?) {
                events += "state saved"
                keys.deleteFailureMacKeysExcept(keepSeq)
            }
        }
        vault = Vault(layout, recording, clock, KDF)
        vault.syncPromotionFolder = { dir ->
            events += "sync ${dir.name}"
            syncDirectory(dir)
        }
        vault.overwriteKeyFile = { file ->
            events += "overwrite"
            overwriteInPlace(file)
        }

        val session = unlocked(P2)

        val order = events.dropWhile { it != "sync ${layout.root.name}" }
        assertEquals(listOf("sync ${layout.root.name}", "overwrite"), order.take(2), "the sync comes right before the overwrite")
        val idxSync = events.indexOf("sync ${layout.root.name}")
        assertEquals("state saved", events.getOrNull(idxSync - 1), "the state is saved right before the sync")
        session.close()
        decoy.key.fill(0)
    }

    @Test
    fun aFolderSyncThatFailsBeforeTheOverwriteStopsThePromotionBeforeAnythingIsOverwrittenAndTheNextUnlockFinishesIt() {
        val (oldBytes, decoy) = setUpForPromotion()
        var overwrites = 0
        vault.syncPromotionFolder = { throw IOException("the folder cannot be synced") }
        vault.overwriteKeyFile = { file ->
            overwrites++
            overwriteInPlace(file)
        }

        assertRejected(attempt(P2))

        assertEquals(0, overwrites, "nothing is overwritten while the state may not be durable")
        assertContentEquals(oldBytes, layout.vaultKey.readBytes())
        assertTrue(layout.promotionMarker.exists())
        assertEquals(1, keyFileTemps().size)
        assertPromotedAfterReopen(oldBytes, decoy)
    }

    @Test
    fun anOverwriteThatFailsWithAnyExceptionStillCommitsThePromotionAndNothingAfterItIsSkipped() {
        val (_, decoy) = setUpForPromotion()
        val sealedKey = File(layout.root, BiometricUnlock.BLOB_FILE_NAME).apply { writeBytes(ByteArray(64) { 7 }) }
        vault.overwriteKeyFile = { throw IllegalStateException("the disk refused") }
        vault.skipCleanupForTesting = true

        val session = unlocked(P2)

        // The commit stands and every step after it ran: the new file is in place, the sealed key is gone, the count is clear.
        assertEquals(VaultKeyFile.digestOf(layout.vaultKey.readBytes()), String(layout.promotionMarker.readBytes(), Charsets.US_ASCII))
        assertEquals(emptyList(), keyFileTemps())
        assertFalse(sealedKey.exists())
        assertEquals(0, vault.failureCountForTesting())
        assertEquals(decoy.wallets, session.listWallets())
        session.close()
        FailureStore.forget(layout.root)
        vault = Vault(layout, keys, clock, KDF)
        assertRejected(attempt(P1))
        assertEquals(decoy.wallets, walletsOf(P2))
        decoy.key.fill(0)
    }

    @Test
    fun aReplaceThatFailsAfterTheOverwriteLeavesAPromotionTheNextUnlockFinishes() {
        val probe = tmp.newFolder("probe")
        probe.setWritable(false)
        val refusesWrites = !probe.canWrite()
        probe.setWritable(true)
        assumeTrue("a folder cannot be made read-only here (running as root)", refusesWrites)
        val (oldBytes, decoy) = setUpForPromotion()
        // The folder refuses the rename and still lets the file in it be written, as a full or read-only folder does.
        vault.overwriteKeyFile = { file ->
            overwriteInPlace(file)
            layout.root.setWritable(false)
        }
        try {
            assertRejected(attempt(P2))
        } finally {
            layout.root.setWritable(true)
        }

        assertTrue(layout.promotionMarker.exists())
        assertEquals(1, keyFileTemps().size)
        assertEquals(emptyList(), filesHolding(oldBytes))
        assertPromotedAfterReopen(oldBytes, decoy)
    }

    @Test
    fun aNewUnlockFileThatIsGoneAfterTheStateNamedItIsUnverifiableAndTheNextUnlockErases() {
        val (_, decoy) = setUpForPromotion()
        vault.overwriteKeyFile = { file ->
            overwriteInPlace(file)
            throw Killed()
        }
        assertFailsWith<Killed> { attempt(P2) }
        keyFileTemps().forEach { it.delete() }

        FailureStore.forget(layout.root)
        vault = Vault(layout, keys, clock, KDF)
        assertEquals(UnlockResult.Erased, attempt(P2))
        assertFalse(vault.exists())
        assertFalse(layout.promotionMarker.exists())
        decoy.key.fill(0)
    }

    @Test
    fun nothingThatRunsBeforeAnUnlockRemovesTheNewUnlockFileOfAnInterruptedPromotion() {
        val (_, decoy) = setUpForPromotion()
        vault.overwriteKeyFile = { file ->
            overwriteInPlace(file)
            throw Killed()
        }
        assertFailsWith<Killed> { attempt(P2) }

        FailureStore.forget(layout.root)
        vault = Vault(layout, keys, clock, KDF)
        // What the gate runs when the app starts.
        assertTrue(vault.exists())
        assertFalse(vault.isPartial())
        assertTrue(runBlocking { vault.finishPendingWipe() })
        runBlocking { vault.waitRemainingMs() }
        VaultRecordNames.rewriteAll(layout.vaultRecords)

        assertEquals(1, keyFileTemps().size, "the new file is still there for the roll forward")
        assertEquals(decoy.wallets, walletsOf(P2))
        decoy.key.fill(0)
    }

    @Test
    fun aCrashHalfWayThroughTheCleanupIsFinishedByTheNextUnlockAndTheVaultIsThatOfOneThatNeverHadADuressPassword() {
        val fresh = freshShape("before", "after")
        create(P1)
        val real = unlocked(P1)
        val wallets = List(3) { real.createWallet() }
        savePhotos(layout, "before")
        val realKey = real.copyOfVaultKey()
        val decoy = setDuress(real, P2)
        savePhotos(layout, "after")
        real.close()

        // The process dies as the second wallet folder of the old vault is released. Unused slots are released too, in any order.
        var armed = false
        val dying = object : KeystoreBackend by keys {
            override fun deleteFolderKey(keyId: String) {
                val before = wallets.count { ownsWallet(keys, layout, realKey, it) }
                keys.deleteFolderKey(keyId)
                if (armed && before == 2 && wallets.count { ownsWallet(keys, layout, realKey, it) } == 1) throw IllegalStateException("The process died.")
            }
        }
        vault = Vault(layout, dying, clock, KDF)
        armed = true
        val promoted = unlocked(P2)
        awaitCleanup()
        promoted.close()
        armed = false

        val kept = wallets.count { ownsWallet(keys, layout, realKey, it) }
        assertTrue(kept in 1 until wallets.size, "the purge stopped half way: $kept of ${wallets.size} old wallets are still there")
        assertTrue(layout.promotionMarker.exists(), "an unfinished cleanup keeps its marker")

        FailureStore.forget(layout.root)
        vault = Vault(layout, keys, clock, KDF)
        val again = unlocked(P2)
        awaitCleanup()

        assertFalse(layout.promotionMarker.exists())
        assertEquals(decoy.wallets, again.listWallets())
        assertTrue(wallets.all { WalletFolder.isUnused(layout.walletDir(it)) })
        assertFalse(ownsWallet(keys, layout, realKey, wallets[0]))
        assertSameShape(fresh, shape(layout, keys))
        again.close()
        realKey.fill(0)
        decoy.key.fill(0)
    }

    @Test
    fun theSessionIsNotBlockedWhileTheCleanupWorksBetweenItsSteps() {
        create(P1)
        val real = unlocked(P1)
        real.createWallet()
        setDuress(real, P2).key.fill(0)
        real.close()
        // The second call of this is the cleanup's: it runs after the purge, so it must run outside the session's lock.
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        val slow = object : KeystoreBackend by keys {
            override fun deleteBiometricKey() {
                if (++calls == 2) {
                    parked.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                keys.deleteBiometricKey()
            }
        }
        vault = Vault(layout, slow, clock, KDF)

        val promoted = unlocked(P2)
        assertTrue(parked.await(10, TimeUnit.SECONDS), "the cleanup reached its last step")
        val listing = Executors.newSingleThreadExecutor().submit<List<String>> { promoted.listWallets() }
        try {
            assertEquals(1, listing.get(2, TimeUnit.SECONDS).size, "the promoted vault answers while the cleanup runs")
        } finally {
            release.countDown()
        }
        awaitCleanup()
        assertFalse(layout.promotionMarker.exists())
        promoted.close()
    }

    @Test
    fun aPhotoSavedWhileTheCleanupRunsKeepsItsTwinAndAHalfWrittenOneIsNotShredded() {
        create(P1)
        val real = unlocked(P1)
        setDuress(real, P2).key.fill(0)
        real.close()
        val sawPhotoStep = CountDownLatch(1)
        var calls = 0
        val watching = object : KeystoreBackend by keys {
            override fun deleteBiometricKey() {
                // The cleanup's call comes after its photo step.
                if (++calls == 2) sawPhotoStep.countDown()
                keys.deleteBiometricKey()
            }
        }
        vault = Vault(layout, watching, clock, KDF)
        layout.makePhotoFolders()
        // A cover save that is half done: the photo is in the old lane, and its twin is not in the promoted lane yet.
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        PhotoStore.sealer.execute {
            File(layout.photoDir(0), "new").writeBytes(ByteArray(8))
            started.countDown()
            finish.await(10, TimeUnit.SECONDS)
            File(layout.photoDir(1), "new").writeBytes(ByteArray(8))
        }
        assertTrue(started.await(10, TimeUnit.SECONDS))

        val promoted = unlocked(P2)
        // The cleanup waits for the save: it does not get past the photo step while the save runs.
        assertFalse(sawPhotoStep.await(700, TimeUnit.MILLISECONDS), "the photo step ran while a save was half done")
        assertTrue(File(layout.photoDir(0), "new").exists(), "the half-written photo was not shredded")
        finish.countDown()
        awaitCleanup()

        assertTrue(File(layout.photoDir(0), "new").exists() && File(layout.photoDir(1), "new").exists(), "the saved photo kept its twin")
        assertFalse(layout.promotionMarker.exists())
        promoted.close()
    }

    @Test
    fun theCleanupWithSixteenWalletsLeavesTheShapeOfAFreshVaultAndNeverBlocksTheSessionLong() {
        val fresh = freshShape("before", "after")
        create(P1)
        val real = unlocked(P1)
        repeat(16) { real.createWallet() }
        savePhotos(layout, "before")
        val decoy = setDuress(real, P2)
        savePhotos(layout, "after")
        real.close()
        val folders = layout.wallets.list().orEmpty().size

        val promoteStart = System.nanoTime()
        val promoted = unlocked(P2)
        val promoteMs = (System.nanoTime() - promoteStart) / 1_000_000
        // A reader on the promoted session, as the first screen is: how long does one call wait during the cleanup?
        var worstMs = 0L
        var reads = 0
        val cleanupStart = System.nanoTime()
        val done = AtomicBoolean(false)
        val reader = Thread {
            while (!done.get()) {
                val start = System.nanoTime()
                promoted.listWallets()
                worstMs = maxOf(worstMs, (System.nanoTime() - start) / 1_000_000)
                reads++
                Thread.sleep(2)
            }
        }
        reader.start()
        awaitCleanup()
        done.set(true)
        reader.join()
        val cleanupMs = (System.nanoTime() - cleanupStart) / 1_000_000
        println("promotion with 16 wallets: unlock ${promoteMs} ms, cleanup ${cleanupMs} ms, $reads reads, longest wait of one read ${worstMs} ms")

        assertFalse(layout.promotionMarker.exists())
        // The store keeps the folder count it grew to for 16 wallets, so it is compared apart from the folders: each is the decoy's wallet or unused.
        val after = shape(layout, keys)
        assertEquals(fresh.rootFiles, after.rootFiles)
        assertEquals(fresh.records, after.records)
        assertEquals(fresh.pool, after.pool)
        assertEquals(fresh.lanes, after.lanes)
        assertEquals(folders, layout.wallets.list().orEmpty().size)
        assertEquals(decoy.wallets, promoted.listWallets())
        decoy.key.fill(0)
        assertTrue(worstMs < cleanupMs || reads == 0, "a read waited as long as the whole cleanup")
        promoted.close()
    }

    /** A process that dies at the point where it is thrown: nothing catches it, so no cleanup after it runs. */
    private class Killed : Error()

    private fun create(password: String) {
        assertEquals(CreateResult.Created, runBlocking { vault.create(password.toCharArray()) })
    }

    private fun attempt(password: String): UnlockResult {
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        return runBlocking { vault.unlock(password.toCharArray()) }
    }

    private fun verify(password: String): UnlockResult {
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        return runBlocking { vault.verify(password.toCharArray()) }
    }

    private fun unlocked(password: String): VaultSession {
        val result = attempt(password)
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return (result as UnlockResult.Unlocked).session
    }

    private fun assertRejected(result: UnlockResult) {
        assertEquals(UnlockResult.Rejected, result)
    }

    /** A vault with a Duress Password, closed. Returns the bytes of its `unlock.bin` and the decoy. The caller zeroes the decoy's key. */
    private fun setUpForPromotion(): Pair<ByteArray, Decoy> {
        create(P1)
        val real = unlocked(P1)
        real.createWallet()
        val decoy = setDuress(real, P2)
        real.close()
        return layout.vaultKey.readBytes() to decoy
    }

    /** The temp files that a commit of `unlock.bin` leaves beside it. */
    private fun keyFileTemps(): List<File> =
        layout.root.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("unlock.bin.") && it.name.endsWith(".tmp") }

    /** The files under the test folder that hold [bytes] anywhere in them. */
    private fun filesHolding(bytes: ByteArray): List<File> = tmp.root.walkTopDown().filter { file ->
        file.isFile && file.length() >= bytes.size && file.readBytes().let { data ->
            (0..data.size - bytes.size).any { start -> bytes.indices.all { data[start + it] == bytes[it] } }
        }
    }.toList()

    /**
     * A new process opens the vault that a crash interrupted: the Duress Password's vault is the vault, the old password and the old bytes
     * are gone, and the marker, the temp file and the old vault's files go with the cleanup.
     */
    private fun assertPromotedAfterReopen(oldBytes: ByteArray, decoy: Decoy, onReopen: (Vault) -> Unit = {}) {
        FailureStore.forget(layout.root)
        vault = Vault(layout, keys, clock, KDF)
        onReopen(vault)
        val promoted = unlocked(P2)
        awaitCleanup()

        assertEquals(decoy.wallets, promoted.listWallets())
        assertEquals(1, promoted.side)
        assertFalse(layout.promotionMarker.exists())
        assertEquals(emptyList(), keyFileTemps())
        assertEquals(emptyList(), filesHolding(oldBytes), "the old unlock.bin is nowhere on disk")
        promoted.close()
        assertRejected(attempt(P1))
        assertEquals(decoy.wallets, walletsOf(P2))
        decoy.key.fill(0)
    }

    private fun walletsOf(password: String): List<String> {
        val session = unlocked(password)
        awaitCleanup()
        return session.listWallets().also { session.close() }
    }

    private fun awaitCleanup() = runBlocking { vault.awaitCleanup() }

    /** The decoy that [setDuress] made: its key and wallet ids, as populate saw them. The caller zeroes the key. */
    private class Decoy(val key: ByteArray, val wallets: List<String>)

    /** Sets the Duress Password with one decoy wallet. */
    private fun setDuress(session: VaultSession, password: String): Decoy {
        var key = ByteArray(0)
        var ids = emptyList<String>()
        val result = runBlocking {
            vault.setDuress(session, password.toCharArray()) { decoy ->
                decoy.createWallet()
                key = decoy.copyOfVaultKey()
                ids = decoy.listWallets()
            }
        }
        assertEquals(DuressResult.Enabled, result)
        // The photo copies are made after the setup returns.
        runBlocking { vault.awaitPhotoCopies() }
        return Decoy(key, ids)
    }

    /** Both photo folders get the same two files, as the cover makes them, so the shape of the folders counts for something. */
    private fun savePhotos(layout: VaultLayout, tag: String = "x") {
        listOf("$tag-a" to 100, "$tag-b" to 200).forEach { (name, size) ->
            listOf(0, 1).forEach { side -> File(layout.photoDir(side).apply { mkdirs() }, name).writeBytes(ByteArray(size)) }
        }
    }

    private var freshCount = 0

    private fun layoutAt(name: String) = VaultLayout(File(tmp.root, name), File(tmp.root, "$name-photos"))

    /** What a vault that never had a Duress Password looks like with one wallet and the two photos. */
    private fun freshShape(vararg photoTags: String = arrayOf("x")): Shape {
        val freshLayout = layoutAt("fresh${freshCount++}")
        val freshKeys = MemoryKeystore()
        val freshVault = Vault(freshLayout, freshKeys, FakeClock(), KDF)
        runBlocking { freshVault.create(P[0].toCharArray()) }
        val session = (runBlocking { freshVault.unlock(P[0].toCharArray()) } as UnlockResult.Unlocked).session
        session.createWallet()
        photoTags.forEach { savePhotos(freshLayout, it) }
        val shape = shape(freshLayout, freshKeys)
        session.close()
        return shape
    }

    private fun assertSameShape(expected: Shape, actual: Shape, message: String = "") {
        assertEquals(expected.rootFiles, actual.rootFiles, "$message root files")
        assertEquals(expected.records, actual.records, "$message record files")
        assertEquals(expected.pool, actual.pool, "$message review pool")
        assertEquals(expected.folders, actual.folders, "$message wallet folders")
        assertEquals(expected.lanes, actual.lanes, "$message photo lanes")
        assertEquals(expected.photoKeys, actual.photoKeys, "$message photo keys")
        assertEquals(expected.folderKeys, actual.folderKeys, "$message folder keys")
    }

    /** The names that exist whatever the vault holds, and the sizes of everything else. Names that a key makes, or that are random, are not compared. */
    private data class Shape(
        val rootFiles: List<String>,
        val records: List<Long>,
        val pool: List<Long>,
        val folders: List<String>,
        val lanes: List<List<Long>>,
        val photoKeys: List<Long>,
        val folderKeys: Int,
    )

    private fun shape(layout: VaultLayout, keys: MemoryKeystore): Shape {
        fun sizes(dir: File) = dir.listFiles().orEmpty().filter { it.isFile }.map { it.length() }.sorted()
        return Shape(
            rootFiles = layout.root.listFiles().orEmpty().filter { it.isFile }.map { "${it.name}:${it.length()}" }.sorted(),
            records = sizes(layout.vaultRecords),
            pool = sizes(layout.reviewPool),
            folders = layout.wallets.listFiles().orEmpty().filter { it.isDirectory }
                .map { dir -> dir.listFiles().orEmpty().map { "${it.name}:${it.length()}" }.sorted().toString() }.sorted(),
            lanes = listOf(0, 1).map { sizes(layout.photoDir(it)) },
            photoKeys = listOf(0, 1).map { layout.photoKeyFile(it).length() },
            folderKeys = keys.folderKeyIds().size,
        )
    }

    private companion object {
        const val P1 = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val P2 = "Hj3\$kP9wQz!Lm6Tn2Ysd"
        const val P3 = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val P = listOf(P1, P2, P3, "Zt4&Mv8!Cn1%Bx6^Lk2Ra", "Wc6!Jd9#Fp2@Hy7&Gb3Ks")
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
