package app.lensly.core.vault

import java.io.File
import java.security.SecureRandom
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

        assertEquals(0, session.side)
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
    fun aClaimThatFailsWithAnyExceptionIsRolledBackLikeAnIoFailure() {
        // The claim is the first to ask for randomness after the decoy's commit, and its RuntimeException is neither an IOException nor a
        // VaultException.
        val random = ClaimBreaker()
        var armed = false
        var saves = 0
        val counting = object : KeystoreBackend by keys {
            override fun createFailureMacKey(seq: Long): SecretKey {
                if (armed && ++saves == 1) random.failNext = true
                return keys.createFailureMacKey(seq)
            }
        }
        var erasedCalls = 0
        vault = Vault(layout, counting, clock, KDF, onErased = { erasedCalls++ }, random = random)
        createVault()
        val real = unlocked(attempt(APP))
        real.createWallet()

        val result = setDuressArmed(real) { armed = true }

        // The setup fails and the vault stays as it was: the App Password still opens it and the Duress Password opens nothing.
        assertEquals(DuressResult.Failed, result)
        assertEquals(0, erasedCalls)
        real.close()
        assertRejected(unlockAfterDelay(DURESS))
        unlocked(unlockAfterDelay(APP)).close()
    }

    @Test
    fun aClaimAndARollbackThatFailWithAnyExceptionEraseTheVault() {
        val random = ClaimBreaker()
        var armed = false
        var saves = 0
        val failing = object : KeystoreBackend by keys {
            override fun createFailureMacKey(seq: Long): SecretKey {
                if (!armed) return keys.createFailureMacKey(seq)
                // The decoy's commit is the first save: the claim fails after it, and so does every later save (the rollback).
                if (++saves == 1) random.failNext = true
                if (saves > 1) throw RuntimeException("The rollback broke.")
                return keys.createFailureMacKey(seq)
            }
        }
        var erasedCalls = 0
        vault = Vault(layout, failing, clock, KDF, onErased = { erasedCalls++ }, random = random)
        createVault()
        val real = unlocked(attempt(APP))
        real.createWallet()

        val result = setDuressArmed(real) { armed = true }

        assertEquals(DuressResult.Failed, result)
        assertFalse(layout.root.exists())
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
        assertEquals(0, reopened.side)
        reopened.close()
        // The Duress Password still opens the decoy, which the unlock promotes.
        val promoted = unlocked(unlockAfterDelay(DURESS))
        assertEquals(1, promoted.side)
        assertEquals(1, promoted.listWallets().size)
        promoted.close()
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
        assertEquals(0, reopened.side)
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
        assertEquals(30_000L, runBlocking { vault.failureDelayCapMs() })
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
        val decoy = setDuressKeepingKey(real, wallets = 2)
        assertEquals(DuressResult.Enabled, decoy.result)
        assertEquals(WALLET_SLOT_STEP, walletFolders())

        runBlocking { vault.removeDuress(real) }
        real.close()

        assertEquals(WALLET_SLOT_STEP, walletFolders())
        // The folders stay, but the wallets they held no longer open and no session lists them.
        decoy.wallets.forEach { id ->
            assertFalse(ownsWallet(keys, layout, decoy.key, id))
            assertTrue(layout.walletKeyFile(id).isFile)
        }
        decoy.key.fill(0)
        val reopened = unlocked(unlockAfterDelay(APP))
        decoy.wallets.forEach { id -> assertFalse(reopened.listWallets().contains(id)) }
        reopened.close()
    }

    @Test
    fun replacingTheDuressPasswordRemovesTheOldDecoy() {
        createVault()
        val real = unlocked(attempt(APP))
        real.createWallet()
        val old = setDuressKeepingKey(real, password = DURESS, wallets = 1)
        assertEquals(DuressResult.Enabled, old.result)
        assertEquals(DuressResult.Enabled, setDuress(real, password = DURESS_TWO, wallets = 2))
        real.close()

        assertRejected(unlockAfterDelay(DURESS))
        // The old decoy's folder stays, but its wallet no longer opens.
        assertFalse(ownsWallet(keys, layout, old.key, old.wallets.single()))
        old.key.fill(0)
        // The new decoy is what the Duress Password opens, after it is promoted.
        val promoted = unlocked(unlockAfterDelay(DURESS_TWO))
        assertEquals(2, promoted.listWallets().size)
        assertFalse(promoted.listWallets().contains(old.wallets.single()))
        promoted.close()
        // The real wallet and the new decoy's two wallets fill three slots; the rest stay unused.
        assertEquals(WALLET_SLOT_STEP, walletFolders())
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
    fun theFailureDelayIsSharedByBothPasswordsAndADuressOpenClearsTheCount() {
        createVault()
        val real = unlocked(attempt(APP))
        assertEquals(DuressResult.Enabled, setDuress(real))
        real.close()

        assertRejected(attempt(WRONG))
        // The Duress Password is refused during the delay, exactly as a wrong password is, and the refusal is not counted.
        assertRejected(attempt(DURESS))
        assertEquals(1, vault.failureCountForTesting())

        clock.advance(FailureDelay.LEARNING_CAP_MS)
        val promoted = unlocked(attempt(DURESS))
        assertEquals(1, promoted.side)
        promoted.close()
        // The promoted vault is the vault now, so its right password clears the count as any right password does.
        assertEquals(0, vault.failureCountForTesting())
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
    fun removingTheDuressPasswordRemovesTheDecoysPhotos() {
        createVault()
        val real = unlocked(attempt(APP))
        assertEquals(DuressResult.Enabled, setDuress(real))
        val realPhoto = savePhoto(side = 0)
        val decoyPhoto = savePhoto(side = 1)

        runBlocking { vault.removeDuress(real) }
        real.close()

        assertTrue(realPhoto.exists())
        assertFalse(decoyPhoto.exists())
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

    /** A photo in the media folder of [side], as the camera or the gallery would keep it. */
    private fun savePhoto(side: Int): File =
        File(layout.photoDir(side).apply { mkdirs() }, "IMG_1.jpg").apply { writeBytes(ByteArray(1)) }

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

    /** What [setDuressKeepingKey] found: the result, and the decoy's key and wallet ids as [populate] saw them. */
    private class KeptDecoy(val result: DuressResult, val key: ByteArray, val wallets: List<String>)

    /** Sets the Duress Password with [wallets] decoy wallets, and keeps the decoy's key and wallet ids. The caller zeroes the key. */
    private fun setDuressKeepingKey(session: VaultSession, password: String = DURESS, wallets: Int = 1): KeptDecoy {
        var key = ByteArray(0)
        var ids = emptyList<String>()
        val result = runBlocking {
            vault.setDuress(session, password.toCharArray()) { decoy ->
                repeat(wallets) { decoy.createWallet() }
                key = decoy.copyOfVaultKey()
                ids = decoy.listWallets()
            }
        }
        return KeptDecoy(result, key, ids)
    }

    /** Like [setDuress], with [arm] run once the decoy is populated, so the next save of the failure state is the decoy's commit. */
    private fun setDuressArmed(session: VaultSession, arm: () -> Unit): DuressResult = runBlocking {
        vault.setDuress(session, DURESS.toCharArray()) { decoy ->
            decoy.createWallet()
            arm()
        }
    }

    /** The next request for randomness fails with a RuntimeException. The claim of the spare record files is the first one after the decoy's commit. */
    private class ClaimBreaker : SecureRandom() {
        var failNext = false

        override fun nextBytes(bytes: ByteArray) {
            if (failNext) {
                failNext = false
                throw RuntimeException("The claim broke.")
            }
            super.nextBytes(bytes)
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

    /** Every file under the vault root, by path, with its bytes. */
    private fun fileContents(): Map<String, List<Byte>> =
        layout.root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(layout.root).path to it.readBytes().toList() }

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
        const val DURESS_NFC = "Gh5%Rt8!Yu2#Pk6-Caf\u00e9"
        const val DURESS_NFD = "Gh5%Rt8!Yu2#Pk6-Cafe\u0301"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
