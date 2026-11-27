package app.lensly.core.vault

import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Vault format 4: each slot's software key needs the Argon2 output through the slot key, `unlock.bin` is tied to the Failure Delay state,
 * an unverifiable vault is erased and not kept, and the folder of vault records has the same files with or without a Decoy Vault.
 */
class VaultFormatV4Test {

    @get:Rule
    val tmp = TemporaryFolder()

    /** One vault with its own folder, clock and Keystore. */
    private inner class Rig(name: String, wrap: (KeystoreBackend) -> KeystoreBackend = { it }) {
        val clock = FakeClock()
        val keys = MemoryKeystore()
        val layout = VaultLayout(File(tmp.root, name))
        var erased = 0

        /** True while the boot count cannot be read, which makes the Failure Delay state unreadable. */
        var stateUnreadable = false
        val vault = Vault(
            layout,
            wrap(keys),
            clock,
            KDF,
            readBootCount = { if (stateUnreadable) throw VaultException("The boot count cannot be read.") else null },
            onErased = { erased++ },
        )

        fun create(password: String = APP) {
            assertEquals(CreateResult.Created, runBlocking { vault.create(password.toCharArray()) })
        }

        fun attempt(password: String): UnlockResult = runBlocking { vault.unlock(password.toCharArray()) }

        fun unlocked(password: String): VaultSession {
            val result = attempt(password)
            assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
            return result.session
        }

        fun setDuress(real: VaultSession) {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(real, DURESS.toCharArray()) { it.createWallet() } })
        }

        /** Writes [bytes] as `unlock.bin` and records it as the file last written, as a commit does. */
        fun replaceKeyFile(bytes: ByteArray) {
            layout.vaultKey.writeBytes(bytes)
            val store = FailureStore(layout.root, keys)
            store.recordKeyFile(store.load(clock), VaultKeyFile.digestOf(bytes), clock)
        }

        fun header(): VaultHeader = checkNotNull(VaultHeader.decodeFile(layout.header.readBytes()) { keys.headerMac(it) })

        fun slots(): List<ByteArray> = VaultKeyFile.decode(layout.vaultKey.readBytes()).slots
    }

    // The slot key sees the Argon2 output, never the salt alone.

    @Test
    fun theKeystoreMacsTheArgonOutputAndNotTheSalt() {
        val inputs = mutableListOf<ByteArray>()
        val rig = Rig("a") { keys -> recording(keys, inputs) }
        rig.create()

        val salt = rig.header().salt
        val stretched = Argon2Kdf.derive(APP.toCharArray(), salt, KDF)
        assertTrue(inputs.none { it.contentEquals(salt) }, "The slot key was asked for a MAC of the salt.")
        assertContentEquals(stretched, inputs.single())
    }

    @Test
    fun everyPasswordGuessAsksTheKeystoreForADifferentValue() {
        val inputs = mutableListOf<ByteArray>()
        val rig = Rig("a") { keys -> recording(keys, inputs) }
        rig.create()
        inputs.clear()

        rig.attempt(WRONG)
        rig.clock.advance(FailureDelay.LEARNING_CAP_MS)
        rig.attempt(APP)

        assertEquals(2, inputs.size)
        assertFalse(inputs[0].contentEquals(inputs[1]), "Two different guesses gave the Keystore the same input.")
    }

    @Test
    fun aSoftKeyFromOneKeystoreAnswerForTheSaltOpensNoSlot() {
        val rig = Rig("a")
        rig.create()
        val salt = rig.header().salt
        val stretched = Argon2Kdf.derive(APP.toCharArray(), salt, KDF)
        // What an attacker holds after asking the Keystore once for the salt: the old key derivation, run on the right password.
        val fromSalt = hkdfSha256(stretched + rig.keys.slotMac(salt), salt = null, info = SLOT_INFO, length = VaultKeyFile.KEY_BYTES)
        val intended = hkdfSha256(stretched + rig.keys.slotMac(stretched), salt = null, info = SLOT_INFO, length = VaultKeyFile.KEY_BYTES)
        val contents = VaultKeyFile.decode(rig.layout.vaultKey.readBytes())

        for (index in contents.slots.indices) {
            assertFailsWith<AEADBadTagException> { Aead.open(fromSalt, VaultKeyFile.SOFTWARE_AAD, contents.sealedOf(index)) }
        }
        // The key the vault derives does open its slot, so the check above is not vacuous.
        assertTrue(contents.slots.indices.any { runCatching { Aead.open(intended, VaultKeyFile.SOFTWARE_AAD, contents.sealedOf(it)) }.isSuccess })
    }

    @Test
    fun theRightPasswordDoesNotOpenUnderAnotherSlotKeyAndTheTryIsCounted() {
        val rig = Rig("a")
        rig.create()
        rig.keys.createSlotKey()

        assertEquals(UnlockResult.Rejected, rig.attempt(APP))
        assertEquals(1, rig.vault.failureCountForTesting())
    }

    @Test
    fun aMissingSlotKeyErasesTheVaultOnTheFirstTry() {
        val rig = Rig("a")
        rig.create()
        rig.keys.removeSlotKey()

        assertEquals(UnlockResult.Erased, rig.attempt(APP))

        assertFalse(rig.layout.exists())
        assertEquals(1, rig.erased)
    }

    // A vault of an older format is rejected and erased, and nothing is read from it.

    @Test
    fun aVersion3KeyFileErasesTheVaultAndRunsTheEraseCallback() {
        val rig = Rig("a")
        rig.create()
        val bytes = rig.layout.vaultKey.readBytes().also { it[4] = 3 }
        rig.replaceKeyFile(bytes)

        assertEquals(UnlockResult.Erased, rig.attempt(APP))

        assertFalse(rig.layout.exists())
        assertEquals(1, rig.erased)
    }

    @Test
    fun aVersion3HeaderErasesTheVaultBeforeAnyPasswordIsChecked() {
        val rig = Rig("a")
        rig.create()
        val bytes = rig.layout.header.readBytes().also { it[4] = 3 }
        rig.layout.header.writeBytes(bytes)

        assertEquals(UnlockResult.Erased, rig.attempt(APP))

        assertFalse(rig.layout.exists())
        assertEquals(1, rig.erased)
    }

    // 1496: an older copy of the key file.

    @Test
    fun anOlderKeyFileDoesNotOpenTheVaultAfterTheAppPasswordChanged() {
        val rig = Rig("a")
        rig.create()
        val older = rig.layout.vaultKey.readBytes()
        val session = rig.unlocked(APP)
        assertEquals(CreateResult.Created, runBlocking { rig.vault.rekey(session, CHANGED.toCharArray()) })
        session.close()
        // The new password opens the vault until the older file is put back.
        rig.unlocked(CHANGED).close()

        rig.layout.vaultKey.writeBytes(older)

        assertEquals(UnlockResult.Erased, rig.attempt(APP))
        assertFalse(rig.layout.exists())
    }

    @Test
    fun anOlderKeyFileDoesNotBringBackARemovedDuressPassword() {
        val rig = Rig("a")
        rig.create()
        val real = rig.unlocked(APP)
        rig.setDuress(real)
        val withDuress = rig.layout.vaultKey.readBytes()
        runBlocking { rig.vault.removeDuress(real) }
        real.close()

        rig.layout.vaultKey.writeBytes(withDuress)

        assertEquals(UnlockResult.Erased, rig.attempt(DURESS))
        assertFalse(rig.layout.exists())
    }

    @Test
    fun theKeyFileLastWrittenStillOpensAfterEveryChange() {
        val rig = Rig("a")
        rig.create()
        val real = rig.unlocked(APP)
        rig.setDuress(real)
        assertEquals(CreateResult.Created, runBlocking { rig.vault.rekey(real, CHANGED.toCharArray()) })
        real.close()

        rig.unlocked(CHANGED).close()
        rig.unlocked(DURESS).close()
    }

    // 1497: the folder of vault records.

    @Test
    fun aDecoyVaultAddsNoFileToTheRecordsFolder() {
        val plain = Rig("plain")
        plain.create()
        writeRecords(plain)

        val withDecoy = Rig("decoy")
        withDecoy.create()
        writeRecords(withDecoy)
        val real = withDecoy.unlocked(APP)
        withDecoy.setDuress(real)
        real.close()
        val decoy = withDecoy.unlocked(DURESS)
        // The decoy writes its photo key on this first unlock, and the shown wrong-try limits once the owner changes them.
        decoy.putVaultRecord(VaultRecordNames.DECOY_SHOWN, byteArrayOf(1))
        decoy.putVaultRecord("settings", byteArrayOf(2))

        assertEquals(recordFiles(plain), recordFiles(withDecoy))
        assertEquals(2 * VaultRecordNames.KNOWN.size, recordFiles(withDecoy))

        runBlocking { withDecoy.vault.wipeDecoy(decoy) }
        decoy.close()
        assertEquals(recordFiles(plain), recordFiles(withDecoy))
    }

    @Test
    fun theRecordsFolderKeepsItsFilesWhenTheDuressPasswordIsRemovedOrReplaced() {
        val rig = Rig("a")
        rig.create()
        val before = recordFiles(rig)
        val real = rig.unlocked(APP)
        rig.setDuress(real)
        assertEquals(before, recordFiles(rig))
        rig.setDuress(real)
        assertEquals(before, recordFiles(rig))
        runBlocking { rig.vault.removeDuress(real) }
        assertEquals(before, recordFiles(rig))
        real.close()
    }

    @Test
    fun noRecordFileNameCanBeComputedWithoutAKeyAndTheSizesNeverShowADecoy() {
        val rig = Rig("a")
        rig.create()
        val without = recordSizes(rig)
        assertEquals(0, publicRecordNames(rig))

        val real = rig.unlocked(APP)
        rig.setDuress(real)
        assertEquals(without, recordSizes(rig))
        assertEquals(0, publicRecordNames(rig))

        runBlocking { rig.vault.removeDuress(real) }
        assertEquals(without, recordSizes(rig))
        assertEquals(0, publicRecordNames(rig))
        real.close()
    }

    @Test
    fun aRecordStillReadsAsAbsentUntilItIsWritten() {
        val rig = Rig("a")
        rig.create()
        val session = rig.unlocked(APP)

        assertEquals(null, session.getVaultRecord("settings"))
        session.putVaultRecord("settings", ByteArray(0))
        assertContentEquals(ByteArray(0), session.getVaultRecord("settings"))
        session.close()
    }

    // 1494: states that cannot be verified are erased, not kept.

    @Test
    fun aKeyFileOfTheWrongSizeErasesTheVaultOnTheFirstTry() {
        val rig = Rig("a")
        rig.create()
        // The failure state names this file, so only its size and magic make it unreadable.
        rig.replaceKeyFile(rig.layout.vaultKey.readBytes().copyOf(VaultKeyFile.FILE_BYTES - 1))

        assertEquals(UnlockResult.Erased, rig.attempt(APP))

        assertFalse(rig.layout.exists())
        assertEquals(1, rig.erased)
    }

    @Test
    fun aKeyFileWithTheWrongMagicErasesTheVaultOnTheFirstTry() {
        val rig = Rig("a")
        rig.create()
        rig.replaceKeyFile(rig.layout.vaultKey.readBytes().also { it[0] = 0 })

        assertEquals(UnlockResult.Erased, rig.attempt(WRONG))

        assertFalse(rig.layout.exists())
    }

    @Test
    fun twoSlotsThatOpenWithOnePasswordErasesTheVault() {
        val rig = Rig("a")
        rig.create()
        val session = rig.unlocked(APP)
        val vaultKey = session.copyOfVaultKey()
        session.close()
        val real = rig.slots()[VaultKeyFile.ownerIndex(VaultKeyFile.decode(rig.layout.vaultKey.readBytes()), vaultKey)]
        rig.replaceKeyFile(VaultKeyFile.encode(listOf(real, real), SecureRandom()))

        assertEquals(UnlockResult.Erased, rig.attempt(APP))

        assertFalse(rig.layout.exists())
    }

    @Test
    fun aSlotWithAnUnknownRoleErasesTheVault() {
        val rig = Rig("a")
        rig.create()
        val session = rig.unlocked(APP)
        val vaultKey = session.copyOfVaultKey()
        session.close()
        val slot = slotWithRole(rig, APP, vaultKey, role = 9)
        rig.clock.advance(FailureDelay.LEARNING_CAP_MS)
        rig.replaceKeyFile(VaultKeyFile.encode(listOf(slot, VaultKeyFile.randomSlot(SecureRandom())), SecureRandom()))

        assertEquals(UnlockResult.Erased, rig.attempt(APP))

        assertFalse(rig.layout.exists())
    }

    @Test
    fun aCancelledDeviceAuthenticationKeepsTheVault() {
        val rig = Rig("a")
        rig.create()
        rig.keys.hardwareAuthRequired = true

        assertFailsWith<VaultAuthRequiredException> { rig.attempt(APP) }

        assertTrue(rig.layout.exists())
        assertEquals(0, rig.erased)
    }

    @Test
    fun vaultFilesWithoutAWholeVaultAreErasedAtTheGate() {
        val rig = Rig("a")
        rig.create()
        assertTrue(rig.layout.header.delete())
        assertTrue(rig.vault.isPartial())

        assertTrue(runBlocking { rig.vault.finishPendingWipe() })

        assertTrue(rig.vault.isBeforeSetup())
        assertEquals(1, rig.erased)
    }

    // The key file's commit reaches the disk, and the biometric unlock reads the Failure Delay state best effort.

    @Test
    fun aKeyFileCommitSyncsTheVaultFolderAndAFailedSyncDoesNotNameTheOldFileAgain() {
        val rig = Rig("a")
        rig.create()
        val session = rig.unlocked(APP)
        val folder = rig.layout.root
        // A folder that can be written and entered but not opened for reading: only the sync of the folder itself fails.
        folder.setReadable(false)
        try {
            assumeFalse("The folder is still readable, so its sync cannot fail here.", folder.canRead())
            assertFailsWith<java.io.IOException> { runBlocking { rig.vault.rekey(session, CHANGED.toCharArray()) } }
        } finally {
            folder.setReadable(true)
        }
        session.close()

        // The new file is in place and the state names it, so the vault opens under the new password and is not erased.
        rig.unlocked(CHANGED).close()
        assertEquals(0, rig.erased)
    }

    @Test
    fun anUnreadableFailureStateDoesNotStopTheBiometricUnlockAndDoesNotResetTheCount() {
        val rig = Rig("a")
        rig.create()
        val session = rig.unlocked(APP)
        val vaultKey = session.copyOfVaultKey()
        session.close()
        assertEquals(UnlockResult.Rejected, rig.attempt(WRONG))
        assertEquals(1, rig.vault.failureCountForTesting())

        rig.stateUnreadable = true
        val opened = assertNotNull(runBlocking { rig.vault.unlockWithVaultKey(vaultKey) })
        opened.close()
        rig.stateUnreadable = false

        assertEquals(1, rig.vault.failureCountForTesting())
    }

    @Test
    fun anUnreadableFailureStateStillErasesAKeyFileThatIsNotOne() {
        val rig = Rig("a")
        rig.create()
        val session = rig.unlocked(APP)
        val vaultKey = session.copyOfVaultKey()
        session.close()
        rig.layout.vaultKey.writeBytes(rig.layout.vaultKey.readBytes().copyOf(VaultKeyFile.FILE_BYTES - 1))

        rig.stateUnreadable = true
        assertNull(runBlocking { rig.vault.unlockWithVaultKey(vaultKey) })

        assertFalse(rig.layout.exists())
        assertEquals(1, rig.erased)
    }

    @Test
    fun anOlderKeyFileErasesTheVaultOnTheBiometricPathAndTheCountIsNotReset() {
        val rig = Rig("a")
        rig.create()
        val older = rig.layout.vaultKey.readBytes()
        val session = rig.unlocked(APP)
        val vaultKey = session.copyOfVaultKey()
        assertEquals(CreateResult.Created, runBlocking { rig.vault.rekey(session, CHANGED.toCharArray()) })
        session.close()
        assertEquals(UnlockResult.Rejected, rig.attempt(WRONG))
        rig.layout.vaultKey.writeBytes(older)

        assertNull(runBlocking { rig.vault.unlockWithVaultKey(vaultKey) })

        assertFalse(rig.layout.exists())
        assertEquals(1, rig.erased)
    }

    // 1495: keys derived from the vault key are zeroed.

    @Test
    fun aClosedReviewPoolHasZeroedItsIndexKey() {
        val pool = ReviewPool(File(tmp.root, "pool"), ByteArray(32) { (it + 1).toByte() })
        assertNotEquals(0, pool.indexKey.count { it != 0.toByte() })

        pool.close()

        assertTrue(pool.indexKey.all { it == 0.toByte() })
    }

    private fun recording(keys: KeystoreBackend, inputs: MutableList<ByteArray>): KeystoreBackend = object : KeystoreBackend by keys {
        override fun slotMac(message: ByteArray): ByteArray {
            inputs += message.copyOf()
            return keys.slotMac(message)
        }
    }

    private fun writeRecords(rig: Rig) {
        val session = rig.unlocked(APP)
        session.putVaultRecord("settings", byteArrayOf(1))
        session.putVaultRecord("address-book", byteArrayOf(2))
        session.close()
    }

    /** The sorted sizes of the record files: a decoy must not change them. */
    private fun recordSizes(rig: Rig): List<Long> = rig.layout.vaultRecords.listFiles().orEmpty().map { it.length() }.sorted()

    /** How many record files carry a name that anyone can compute: the names that a fixed public key and the record names make. */
    private fun publicRecordNames(rig: Rig): Int {
        val publicKey = "lensly.vault.decoy-record-name".toByteArray(Charsets.US_ASCII)
        val names = VaultRecordNames.KNOWN.map { name ->
            labelledHmac(publicKey, "lensly.vault.record-name\u0000$name").joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".bin"
        }.toSet()
        return rig.layout.vaultRecords.list().orEmpty().count { it in names }
    }

    private fun recordFiles(rig: Rig): Int = rig.layout.vaultRecords.list().orEmpty().size

    /** The real vault's slot sealed under [password]'s key with [role] inside, as a damaged file would hold it. */
    private fun slotWithRole(rig: Rig, password: String, vaultKey: ByteArray, role: Byte): ByteArray {
        val header = rig.header()
        val stretched = Argon2Kdf.derive(password.toCharArray(), header.salt, header.kdf)
        val softKey = hkdfSha256(stretched + rig.keys.slotMac(stretched), salt = null, info = SLOT_INFO, length = VaultKeyFile.KEY_BYTES)
        val hardwareWrapped = Aead.sealWithKeystore(rig.keys.hardwareKey(), VaultKeyFile.HARDWARE_AAD, vaultKey + role)
        return VaultKeyFile.slotOf(vaultKey, Aead.seal(softKey, VaultKeyFile.SOFTWARE_AAD, hardwareWrapped), SecureRandom())
    }

    private companion object {
        const val APP = "TEST_ONLY_q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val WRONG = "TEST_ONLY_Zx8#Vb2!Nm5\$Qw7%Ld4"
        const val DURESS = "TEST_ONLY_Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        const val CHANGED = "TEST_ONLY_Mk3#Jd9!Xc6@Bv1Lq5Sy"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
        val SLOT_INFO = "lensly-vault-slot-v4".toByteArray(Charsets.US_ASCII)
    }
}
