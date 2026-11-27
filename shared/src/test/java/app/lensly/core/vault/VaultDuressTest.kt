package app.lensly.core.vault

import java.io.File
import javax.crypto.SecretKey
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The real vault and its Decoy Vault, on the JVM with an in-memory keystore. Argon2 runs at a small cost here, so the
 * tests are fast. Each unlock moves the clock past any failure delay first, except where a test is about the delay.
 */
class VaultDuressTest {

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
    fun theAppPasswordOpensTheRealVault() {
        createVault()

        val session = unlocked(unlockAfterDelay(APP))

        assertFalse(session.isDecoy)
        assertEquals(emptyList<String>(), session.listWallets())
        session.close()
    }

    @Test
    fun aPasswordThatOpensNeitherVaultIsRejectedAndCounted() {
        createVault()

        assertRejected(attempt(WRONG))
        assertEquals(1, vault.failureCountForTesting())
    }

    @Test
    fun withoutADuressPasswordTheDuressPasswordOpensNothing() {
        createVault()

        assertRejected(attempt(DURESS))
        unlocked(unlockAfterDelay(APP)).close()
    }

    @Test
    fun theDuressPasswordOpensTheDecoyVaultWithItsOwnWallet() {
        createVault()
        val real = unlocked(attempt(APP))
        val realWallet = real.createWallet()
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()

        val decoy = unlocked(unlockAfterDelay(DURESS))
        assertTrue(decoy.isDecoy)
        assertEquals(1, decoy.listWallets().size)
        assertFalse(decoy.listWallets().contains(realWallet))
        decoy.close()

        val reopened = unlocked(unlockAfterDelay(APP))
        assertFalse(reopened.isDecoy)
        assertEquals(listOf(realWallet), reopened.listWallets())
        reopened.close()
    }

    @Test
    fun theTwoVaultsCannotReadEachOthersWallets() {
        createVault()
        val real = unlocked(attempt(APP))
        val realWallet = real.createWallet()
        real.putRecord(realWallet, "secret", byteArrayOf(1, 2, 3))
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()

        val decoy = unlocked(unlockAfterDelay(DURESS))
        val decoyWallet = decoy.listWallets().single()
        decoy.putRecord(decoyWallet, "secret", byteArrayOf(9))
        assertFailsWith<VaultException> { decoy.getRecord(realWallet, "secret") }
        assertFailsWith<VaultException> { decoy.putRecord(realWallet, "secret", byteArrayOf(0)) }
        decoy.deleteWallet(realWallet)
        decoy.close()

        val reopened = unlocked(unlockAfterDelay(APP))
        assertContentEquals(byteArrayOf(1, 2, 3), reopened.getRecord(realWallet, "secret"))
        reopened.close()
    }

    @Test
    fun theRealVaultsLumioRecordsSurviveSettingAndRemovingDuress() {
        val lumioRecords = VaultRecordNames.KNOWN - VaultRecordNames.PHOTO_KEY
        createVault()
        val real = unlocked(attempt(APP))
        lumioRecords.forEach { real.putVaultRecord(it, it.encodeToByteArray()) }
        assertEquals(DuressResult.Enabled, setDuress(real))
        lumioRecords.forEach { assertContentEquals(it.encodeToByteArray(), real.getVaultRecord(it), "$it after setDuress") }

        runBlocking { vault.removeDuress(real) }
        lumioRecords.forEach { assertContentEquals(it.encodeToByteArray(), real.getVaultRecord(it), "$it after removeDuress") }
        real.close()
    }

    @Test
    fun aDuressPasswordEqualToTheAppPasswordIsRefused() {
        createVault()
        val real = unlocked(attempt(APP))

        assertEquals(DuressResult.SameAsAppPassword, setDuress(real, password = APP))
        real.close()

        assertRejected(unlockAfterDelay(DURESS))
        unlocked(unlockAfterDelay(APP)).close()
    }

    @Test
    fun aDecoyWithoutAWalletIsRefusedAndNothingIsEnabled() {
        createVault()
        val real = unlocked(attempt(APP))

        assertEquals(DuressResult.DecoyEmpty, setDuress(real, wallets = 0))
        real.close()

        assertRejected(unlockAfterDelay(DURESS))
        unlocked(unlockAfterDelay(APP)).close()
    }

    @Test
    fun aWeakDuressPasswordIsRefused() {
        createVault()
        val real = unlocked(attempt(APP))

        assertEquals(
            DuressResult.PolicyRejected(PolicyReason.WEAK_SCORE),
            setDuress(real, password = "password1234"),
        )
        real.close()
    }

    @Test
    fun aPopulateThatFailsLeavesNoDecoyBehind() {
        createVault()
        val real = unlocked(attempt(APP))

        val made = mutableListOf<String>()
        var abandonedDecoyKey: ByteArray? = null
        val result = runBlocking {
            vault.setDuress(real, DURESS.toCharArray()) { decoy ->
                abandonedDecoyKey = decoy.copyOfVaultKey()
                made += decoy.createWallet()
                error("The decoy could not be made.")
            }
        }

        assertEquals(DuressResult.Failed, result)
        assertEquals(emptyList<String>(), real.listWallets())
        assertEquals(WALLET_SLOT_STEP, walletFolders())
        real.close()
        assertRejected(unlockAfterDelay(DURESS))
        // The folders stay, but the wallets that were made there no longer open.
        val abandoned = checkNotNull(abandonedDecoyKey)
        made.forEach { id -> assertFalse(ownsWallet(keys, layout,abandoned, id)) }
        abandoned.fill(0)
    }

    @Test
    fun aDecoyWhoseRecordFilesCannotBeClaimedIsDroppedAndTheSetupFails() {
        createVault()
        val real = unlocked(attempt(APP))
        real.createWallet()
        val recordFiles = { layout.vaultRecords.listFiles()!!.count { it.isFile } }
        val before = recordFiles()

        // The decoy's second record name is taken by a folder with a file in it, so the rename of a spare onto it fails after the first one.
        val result = runBlocking {
            vault.setDuress(real, DURESS.toCharArray()) { decoy ->
                decoy.createWallet()
                val decoyKey = decoy.copyOfVaultKey()
                val blocked = File(layout.vaultRecords, VaultRecordNames.fileName(decoyKey, VaultRecordNames.KNOWN[1]))
                decoyKey.fill(0)
                File(blocked, "in-the-way").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1))
            }
        }

        assertEquals(DuressResult.Failed, result)
        // No decoy-named file is left half claimed: the folder holds as many files as before, and the Duress Password opens nothing.
        assertEquals(before, recordFiles())
        real.close()
        assertRejected(unlockAfterDelay(DURESS))
        unlocked(unlockAfterDelay(APP)).close()
    }

    @Test
    fun aClaimThatFailsAndARollbackThatFailsEraseTheVault() {
        // After the decoy's slot is committed, every save of the failure state fails: the claim below fails, and so does the rollback.
        var armed = false
        var saves = 0
        val failing = object : KeystoreBackend by keys {
            override fun createFailureMacKey(seq: Long): SecretKey {
                if (armed && ++saves > 1) throw VaultException("The Keystore refused the key.")
                return keys.createFailureMacKey(seq)
            }
        }
        var erasedCalls = 0
        vault = Vault(layout, failing, clock, KDF, onErased = { erasedCalls++ })
        createVault()
        val real = unlocked(attempt(APP))
        real.createWallet()

        val result = runBlocking {
            vault.setDuress(real, DURESS.toCharArray()) { decoy ->
                decoy.createWallet()
                armed = true
                val decoyKey = decoy.copyOfVaultKey()
                val blocked = File(layout.vaultRecords, VaultRecordNames.fileName(decoyKey, VaultRecordNames.KNOWN[1]))
                decoyKey.fill(0)
                File(blocked, "in-the-way").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1))
            }
        }

        // The half-built decoy is not kept: the whole vault is erased, so the Duress Password opens nothing and neither does the App Password.
        assertEquals(DuressResult.Failed, result)
        assertFalse(layout.root.exists())
        assertFalse(vault.exists())
        assertEquals(1, erasedCalls)
        real.close()
        assertRejected(unlockAfterDelay(DURESS))
        assertRejected(unlockAfterDelay(APP))
    }

    @Test
    fun theAppPasswordChangeLeavesTheDecoyWorking() {
        createVault()
        val real = unlocked(attempt(APP))
        real.createWallet()
        assertEquals(DuressResult.Enabled, setDuress(real))

        assertEquals(CreateResult.Created, runBlocking { vault.rekey(real, OTHER.toCharArray()) })
        real.close()

        assertRejected(unlockAfterDelay(APP))
        val reopened = unlocked(unlockAfterDelay(OTHER))
        assertFalse(reopened.isDecoy)
        reopened.close()
        val decoy = unlocked(unlockAfterDelay(DURESS))
        assertTrue(decoy.isDecoy)
        assertEquals(1, decoy.listWallets().size)
        decoy.close()
    }

    @Test
    fun theDuressPasswordCannotBecomeTheAppPassword() {
        createVault()
        val real = unlocked(attempt(APP))
        assertEquals(DuressResult.Enabled, setDuress(real))

        // Refused as a weak password, so the refusal names no second password.
        assertEquals(
            CreateResult.PolicyRejected(PolicyReason.WEAK_SCORE),
            runBlocking { vault.rekey(real, DURESS.toCharArray()) },
        )
        real.close()

        val reopened = unlocked(unlockAfterDelay(APP))
        assertFalse(reopened.isDecoy)
        reopened.close()
    }

    @Test
    fun aDecoyChangesItsOwnEntryPasswordAndNotTheRealOne() {
        createVault()
        val real = unlocked(attempt(APP))
        real.createWallet()
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()

        val decoy = unlocked(unlockAfterDelay(DURESS))
        assertEquals(CreateResult.Created, runBlocking { vault.rekey(decoy, OTHER.toCharArray()) })
        decoy.close()

        // The change above counted as a wrong try, and the wrong Duress Password just now as a second: the wait is past the learning cap.
        assertRejected(afterTheFullWait(DURESS))
        val moved = unlocked(afterTheFullWait(OTHER))
        assertTrue(moved.isDecoy)
        assertEquals(1, moved.listWallets().size)
        moved.close()
        unlocked(afterTheFullWait(APP)).close()
    }

    @Test
    fun aDecoyAskedToTakeTheAppPasswordAsItsOwnKeepsItsOldPasswordAndAnswersAsForAnyOther() {
        createVault()
        val real = unlocked(attempt(APP))
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()

        val decoy = unlocked(unlockAfterDelay(DURESS))
        // The answer is the same as for any other password, so it never says that this one is the real App Password.
        clock.advance(FailureDelay.LEARNING_CAP_MS)
        assertEquals(CreateResult.Created, runBlocking { vault.rekey(decoy, APP.toCharArray()) })
        decoy.close()

        // Nothing was written: the Duress Password still opens the decoy, and the App Password still opens the real vault only.
        assertTrue(unlocked(unlockAfterDelay(DURESS)).also { it.close() }.isDecoy)
        assertFalse(unlocked(unlockAfterDelay(APP)).also { it.close() }.isDecoy)
    }

    @Test
    fun aDecoySettingItsDuressPasswordChangesNothingAndAnswersEnabled() {
        createVault()
        val real = unlocked(attempt(APP))
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()

        val decoy = unlocked(unlockAfterDelay(DURESS))
        val wallet = decoy.listWallets().single()
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(decoy, OTHER.toCharArray()) { } })
        decoy.close()

        assertRejected(unlockAfterDelay(OTHER))
        val same = unlocked(unlockAfterDelay(DURESS))
        assertTrue(same.isDecoy)
        assertEquals(listOf(wallet), same.listWallets())
        same.close()
    }

    @Test
    fun aDecoyRemovingItsDuressPasswordChangesNothing() {
        createVault()
        val real = unlocked(attempt(APP))
        val realWallet = real.createWallet()
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()

        val decoy = unlocked(unlockAfterDelay(DURESS))
        val decoyWallet = decoy.listWallets().single()
        runBlocking { vault.removeDuress(decoy) }
        decoy.close()

        val sameDecoy = unlocked(unlockAfterDelay(DURESS))
        assertEquals(listOf(decoyWallet), sameDecoy.listWallets())
        sameDecoy.close()
        val reopened = unlocked(unlockAfterDelay(APP))
        assertEquals(listOf(realWallet), reopened.listWallets())
        reopened.close()
    }

    @Test
    fun theFailureDelayCapIsChangedOnlyByTheRealAppPassword() {
        createVault()
        assertEquals(FailureCapResult.Saved, runBlocking { vault.setFailureLimits(APP.toCharArray(), 30_000L) })
        assertEquals(30_000L, runBlocking { vault.failureDelayCapMs() })

        val real = unlocked(attempt(APP))
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()

        // Each vault takes only its own password. The Duress Password is not the real App Password, so it cannot save the cap.
        assertEquals(
            FailureCapResult.Rejected,
            runBlocking { vault.setFailureLimits(DURESS.toCharArray(), 10_000L) },
        )
        // The App Password typed into the decoy is not the decoy's password either.
        assertEquals(
            FailureCapResult.Rejected,
            runBlocking { vault.setFailureLimits(APP.toCharArray(), 10_000L, inDecoy = true) },
        )
        assertEquals(30_000L, runBlocking { vault.failureDelayCapMs() })
    }

    @Test
    fun aDecoyCapChangeAppearsToSaveButNeverReachesTheRealCap() {
        createVault()
        assertEquals(FailureCapResult.Saved, runBlocking { vault.setFailureLimits(APP.toCharArray(), 30_000L) })
        val real = unlocked(attempt(APP))
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()

        val decoy = unlocked(unlockAfterDelay(DURESS))
        // The decoy's own password is accepted, so the change looks saved, but the real cap stays where it was.
        assertEquals(
            FailureCapResult.Saved,
            runBlocking { vault.setFailureLimits(DURESS.toCharArray(), 10_000L, inDecoy = true) },
        )
        decoy.close()
        assertEquals(30_000L, runBlocking { vault.failureDelayCapMs() })

        // The real App Password still saves a real change.
        assertEquals(FailureCapResult.Saved, runBlocking { vault.setFailureLimits(APP.toCharArray(), 10_000L) })
        assertEquals(10_000L, runBlocking { vault.failureDelayCapMs() })
    }

    @Test
    fun removingTheDuressPasswordLeavesNothingItOpens() {
        createVault()
        val real = unlocked(attempt(APP))
        val realWallet = real.createWallet()
        assertEquals(DuressResult.Enabled, setDuress(real))

        runBlocking { vault.removeDuress(real) }
        real.close()

        assertRejected(unlockAfterDelay(DURESS))
        val reopened = unlocked(unlockAfterDelay(APP))
        assertEquals(listOf(realWallet), reopened.listWallets())
        reopened.close()
    }

    @Test
    fun removingTheDuressPasswordDeletesTheDecoysWallets() {
        createVault()
        val real = unlocked(attempt(APP))
        real.createWallet()
        assertEquals(DuressResult.Enabled, setDuress(real, wallets = 2))
        assertEquals(WALLET_SLOT_STEP, walletFolders())

        val decoy = unlocked(unlockAfterDelay(DURESS))
        val gone = decoy.listWallets()
        val decoyKey = decoy.copyOfVaultKey()
        decoy.close()
        runBlocking { vault.removeDuress(real) }
        real.close()

        assertEquals(WALLET_SLOT_STEP, walletFolders())
        // The folders stay, but the wallets they held no longer open and no session lists them.
        gone.forEach { id ->
            assertFalse(ownsWallet(keys, layout,decoyKey, id))
            assertTrue(layout.walletKeyFile(id).isFile)
        }
        decoyKey.fill(0)
        val reopened = unlocked(unlockAfterDelay(APP))
        gone.forEach { id -> assertFalse(reopened.listWallets().contains(id)) }
        reopened.close()
    }

    @Test
    fun replacingTheDuressPasswordRemovesTheOldDecoy() {
        createVault()
        val real = unlocked(attempt(APP))
        real.createWallet()
        assertEquals(DuressResult.Enabled, setDuress(real, password = DURESS, wallets = 1))
        val oldDecoy = unlocked(unlockAfterDelay(DURESS))
        val oldWallet = oldDecoy.listWallets().single()
        val oldDecoyKey = oldDecoy.copyOfVaultKey()
        oldDecoy.close()
        assertEquals(DuressResult.Enabled, setDuress(real, password = DURESS_TWO, wallets = 2))
        real.close()

        assertRejected(unlockAfterDelay(DURESS))
        // The old decoy's folder stays, but its wallet no longer opens.
        assertFalse(ownsWallet(keys, layout,oldDecoyKey, oldWallet))
        oldDecoyKey.fill(0)
        val decoy = unlocked(unlockAfterDelay(DURESS_TWO))
        assertEquals(2, decoy.listWallets().size)
        assertFalse(decoy.listWallets().contains(oldWallet))
        decoy.close()
        // The real wallet and the new decoy's two wallets fill three slots; the rest stay unused.
        assertEquals(WALLET_SLOT_STEP, walletFolders())
    }

    @Test
    fun theDecoyCannotReachTheRealSettings() {
        createVault()
        val real = unlocked(attempt(APP))
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()

        val decoy = unlocked(unlockAfterDelay(DURESS))
        decoy.putVaultRecord("settings", byteArrayOf(1, 2, 3))
        decoy.close()

        val reopened = unlocked(unlockAfterDelay(APP))
        assertNull(reopened.getVaultRecord("settings"))
        reopened.putVaultRecord("settings", byteArrayOf(9))
        reopened.close()

        val decoyAgain = unlocked(unlockAfterDelay(DURESS))
        assertContentEquals(byteArrayOf(1, 2, 3), decoyAgain.getVaultRecord("settings"))
        decoyAgain.close()
    }

    @Test
    fun settingADuressPasswordKeepsTheFilesTheSameShape() {
        createVault()
        val before = fixedFileSizes()
        val real = unlocked(attempt(APP))
        real.createWallet()
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()

        assertEquals(before, fixedFileSizes())
        assertEquals(VaultKeyFile.FILE_BYTES.toLong(), layout.vaultKey.length())
    }

    @Test
    fun aDuressUnlockDoesNotChangeTheRealVaultKeyFile() {
        createVault()
        val real = unlocked(attempt(APP))
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()
        val keyFileBefore = layout.vaultKey.readBytes()
        val headerBefore = layout.header.readBytes()

        val decoy = unlocked(unlockAfterDelay(DURESS))
        decoy.createWallet()
        decoy.close()

        assertContentEquals(keyFileBefore, layout.vaultKey.readBytes())
        assertContentEquals(headerBefore, layout.header.readBytes())
    }

    @Test
    fun theFailureDelayIsSharedByBothPasswords() {
        createVault()
        val real = unlocked(attempt(APP))
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()

        assertRejected(attempt(WRONG))
        // The Duress Password is refused during the delay, exactly as a wrong password is, and the refusal is not counted.
        assertRejected(attempt(DURESS))
        assertEquals(1, vault.failureCountForTesting())

        clock.advance(FailureDelay.LEARNING_CAP_MS)
        val decoy = unlocked(attempt(DURESS))
        assertTrue(decoy.isDecoy)
        decoy.close()
        // The decoy open keeps the real vault's count of one, so it does not clear the streak.
        assertEquals(1, vault.failureCountForTesting())
    }

    @Test
    fun aWrongPasswordIsRejectedTheSameWayWithAndWithoutADecoy() {
        createVault()
        assertRejected(attempt(WRONG))

        val real = unlocked(unlockAfterDelay(APP))
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()

        assertRejected(unlockAfterDelay(WRONG))
    }

    @Test
    fun aWipeFromTheDecoyLeavesTheRealVaultAndItsWalletsAlone() {
        createVault()
        val real = unlocked(attempt(APP))
        val realWallet = real.createWallet()
        real.putRecord(realWallet, "secret", byteArrayOf(1, 2, 3))
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()

        val decoy = unlocked(unlockAfterDelay(DURESS))
        val decoyWallet = decoy.listWallets().single()
        decoy.putRecord(decoyWallet, "secret", byteArrayOf(9))
        val shapeBefore = fileShape()

        vault.wipeDecoy(decoy)
        decoy.close()

        // The files keep their names and sizes, so the store cannot show that a wipe happened.
        assertEquals(shapeBefore, fileShape())
        assertTrue(vault.exists())

        // The Duress Password still opens a decoy, but it is empty. A refusal would tell an attacker that a decoy existed.
        val emptied = unlocked(unlockAfterDelay(DURESS))
        assertTrue(emptied.isDecoy)
        assertEquals(emptyList<String>(), emptied.listWallets())
        assertFailsWith<VaultException> { emptied.getRecord(decoyWallet, "secret") }
        emptied.close()

        val reopened = unlocked(unlockAfterDelay(APP))
        assertFalse(reopened.isDecoy)
        assertEquals(listOf(realWallet), reopened.listWallets())
        assertContentEquals(byteArrayOf(1, 2, 3), reopened.getRecord(realWallet, "secret"))
        reopened.close()
    }

    @Test
    fun aRealWipeRemovesTheRealVaultAndTheDecoyVault() {
        createVault()
        val real = unlocked(attempt(APP))
        real.createWallet()
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()

        vault.wipe()

        assertFalse(vault.exists())
        assertFalse(layout.root.exists())
        assertRejected(attempt(DURESS))
        assertRejected(attempt(APP))
    }

    @Test
    fun aDecoyWipeRemovesOnlyTheDecoysPhotos() {
        createVault()
        val real = unlocked(attempt(APP))
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()
        val realPhoto = savePhoto(decoy = false)
        val decoyPhoto = savePhoto(decoy = true)

        val decoy = unlocked(unlockAfterDelay(DURESS))
        vault.wipeDecoy(decoy)
        decoy.close()

        assertTrue(realPhoto.exists())
        assertFalse(decoyPhoto.exists())
    }

    @Test
    fun removingTheDuressPasswordRemovesTheDecoysPhotos() {
        createVault()
        val real = unlocked(attempt(APP))
        assertEquals(DuressResult.Enabled, setDuress(real))
        val realPhoto = savePhoto(decoy = false)
        val decoyPhoto = savePhoto(decoy = true)

        runBlocking { vault.removeDuress(real) }
        real.close()

        assertTrue(realPhoto.exists())
        assertFalse(decoyPhoto.exists())
    }

    @Test
    fun aDecoyOpenKeepsTheRealVaultsWrongTryCount() {
        createVault()
        val real = unlocked(attempt(APP))
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()
        assertRejected(afterTheFullWait(WRONG))
        assertRejected(afterTheFullWait(WRONG))

        val decoy = unlocked(afterTheFullWait(DURESS))

        assertTrue(decoy.isDecoy)
        decoy.close()
        assertEquals(2, vault.failureCountForTesting())
    }

    @Test
    fun theTriesLeftWarningIsHiddenAfterADecoyOpenAndReturnsAtTheNextWrongTry() {
        createVault()
        val real = unlocked(attempt(APP))
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()
        assertEquals(FailureCapResult.Saved, runBlocking { vault.setFailureLimits(APP.toCharArray(), null, wipeAfter = 4) })
        assertRejected(afterTheFullWait(WRONG))
        assertRejected(afterTheFullWait(WRONG))
        assertEquals(2, triesLeft())

        unlocked(afterTheFullWait(DURESS)).close()
        assertNull(triesLeft())

        assertRejected(afterTheFullWait(WRONG))
        assertEquals(3, vault.failureCountForTesting())
        assertEquals(1, triesLeft())
    }

    @Test
    fun aRealOpenClearsTheWrongTryCountAndHidesTheWarning() {
        createVault()
        assertRejected(afterTheFullWait(WRONG))
        assertRejected(afterTheFullWait(WRONG))

        unlocked(afterTheFullWait(APP)).close()

        assertEquals(0, vault.failureCountForTesting())
        assertNull(triesLeft())
    }

    /** A photo in the real vault's or the Decoy Vault's folder, as the camera or the gallery would keep it. */
    private fun savePhoto(decoy: Boolean): File =
        File(layout.photoDir(decoy).apply { mkdirs() }, "IMG_1.jpg").apply { writeBytes(ByteArray(1)) }

    private fun createVault() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    private fun attempt(password: String): UnlockResult = runBlocking { vault.unlock(password.toCharArray()) }

    /** Moves the clock past the longest wait the default cap allows, then tries [password]. */
    private fun afterTheFullWait(password: String): UnlockResult {
        clock.advance(FailureDelay.DEFAULT_CAP_MS)
        return attempt(password)
    }

    private fun triesLeft(): Int? = runBlocking { vault.triesBeforeErase() }

    /** Moves the clock past any failure delay, then tries [password]. */
    private fun unlockAfterDelay(password: String): UnlockResult {
        clock.advance(FailureDelay.LEARNING_CAP_MS)
        return attempt(password)
    }

    /** Sets the Duress Password from [session], with [wallets] wallets in the decoy. */
    private fun setDuress(
        session: VaultSession,
        password: String = DURESS,
        wallets: Int = 1,
    ): DuressResult = runBlocking {
        vault.setDuress(session, password.toCharArray()) { decoy ->
            repeat(wallets) { decoy.createWallet() }
        }
    }

    private fun unlocked(result: UnlockResult): VaultSession {
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return (result as UnlockResult.Unlocked).session
    }

    private fun assertRejected(result: UnlockResult) {
        assertTrue(result is UnlockResult.Rejected, "Expected Rejected but was $result")
    }

    /** The sizes of the files that exist whatever the vault holds. Wallet and record folders are not counted. */
    private fun fixedFileSizes(): Map<String, Long> =
        listOf(layout.header, layout.vaultKey, File(layout.root, "failures.bin"), File(layout.root, "failures.bak"))
            .associate { it.name to it.length() }

    /** The number of wallet folders, whether they hold a wallet or are unused. */
    private fun walletFolders(): Int = layout.wallets.listFiles()?.count { it.isDirectory } ?: 0

    /** Every file under the vault root, by path, with its size. Contents are not read, so a wipe can be compared by shape. */
    private fun fileShape(): Map<String, Long> =
        layout.root.walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(layout.root).path to it.length() }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val OTHER = "Hj3\$kP9wQz!Lm6Tn2Ysd"
        const val WRONG = "Zx8#Vb2!Nm5\$Qw7%Ld4"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        const val DURESS_TWO = "Zt4&Mv8!Cn1%Bx6^Lk2Ra"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
