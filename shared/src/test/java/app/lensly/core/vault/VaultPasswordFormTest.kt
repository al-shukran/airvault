package app.lensly.core.vault

import java.io.File
import java.security.SecureRandom
import java.text.Normalizer as JavaNormalizer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The same visible password, typed as NFC or as NFD, opens the same vault. A new or changed password is stored from its NFC form. An unlock
 * tries the typed text as it came and then its NFC form, and the whole check is one try toward the erase. A vault made by 1.2.0 from the raw
 * text still opens with that text.
 */
class VaultPasswordFormTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private val kdfRuns = Counter()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    private class Counter {
        var runs = 0
    }

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"))
        // Every Argon2 derivation of a vault asks the slot key once, so the count of those calls is the count of derivations.
        val counting = object : KeystoreBackend by keys {
            override fun slotMac(message: ByteArray): ByteArray {
                kdfRuns.runs++
                return keys.slotMac(message)
            }
        }
        vault = Vault(layout, counting, clock, KDF)
    }

    @Test
    fun anNfdPasswordOpensAVaultMadeWithTheNfcText() {
        create(APP_NFC)

        unlocked(APP_NFD).close()
    }

    @Test
    fun anNfcPasswordOpensAVaultMadeWithTheNfdText() {
        create(APP_NFD)

        unlocked(APP_NFC).close()
    }

    @Test
    fun aVaultMadeByTheOldVersionFromRawNfdTextStillOpensWithThatText() {
        create(ASCII)
        storeFromRawText(typed = ASCII, stored = ASCII, raw = APP_NFD)

        val session = unlocked(APP_NFD)
        assertFalse(session.isDecoy)
        session.close()
    }

    @Test
    fun aVaultMadeByTheOldVersionFromRawNfcTextStillOpensWithBothForms() {
        create(ASCII)
        storeFromRawText(typed = ASCII, stored = ASCII, raw = APP_NFC)

        unlocked(APP_NFC).close()
        unlocked(APP_NFD).close()
    }

    @Test
    fun aWrongPasswordCountsOnceWhateverFormIsTyped() {
        create(APP_NFC)

        assertEquals(UnlockResult.Rejected, attempt(WRONG_NFD))
        assertEquals(1, vault.failureCountForTesting())
        assertEquals(UnlockResult.Rejected, attempt(WRONG_NFC))
        assertEquals(2, vault.failureCountForTesting())
    }

    @Test
    fun anNfdPasswordThatOpensOnTheSecondFormClearsTheCountOnce() {
        create(APP_NFC)
        assertEquals(UnlockResult.Rejected, attempt(WRONG_NFC))
        assertEquals(1, vault.failureCountForTesting())

        unlocked(APP_NFD).close()

        assertEquals(0, vault.failureCountForTesting())
    }

    @Test
    fun theLastAllowedTryErasesOnceWithAnNfdWrongPassword() {
        create(APP_NFC)
        assertEquals(FailureCapResult.Saved, runBlocking { vault.setFailureLimits(APP_NFC.toCharArray(), null, wipeAfter = 3) })
        assertEquals(UnlockResult.Rejected, attempt(WRONG_NFD))
        assertEquals(2, runBlocking { vault.triesBeforeErase() }, "one NFD try counts once, not twice")
        clock.advance(FailureDelay.DEFAULT_CAP_MS)
        assertEquals(UnlockResult.Rejected, attempt(WRONG_NFD))
        clock.advance(FailureDelay.DEFAULT_CAP_MS)

        assertEquals(UnlockResult.Erased, attempt(WRONG_NFD))
        assertFalse(layout.exists())
    }

    @Test
    fun theDuressPasswordOpensTheDecoyInEitherForm() {
        create(APP_NFC)
        setDuress(APP_NFC, DURESS_NFD)

        for (typed in listOf(DURESS_NFC, DURESS_NFD)) {
            val decoy = unlocked(typed)
            assertTrue(decoy.isDecoy)
            decoy.close()
        }
        assertFalse(unlocked(APP_NFD).isDecoy)
    }

    @Test
    fun aDecoyMadeByTheOldVersionFromRawNfdTextStillOpensWithThatText() {
        create(APP_NFC)
        setDuress(APP_NFC, DURESS_NFC)
        storeFromRawText(typed = DURESS_NFC, stored = DURESS_NFC, raw = DURESS_NFD)

        val decoy = unlocked(DURESS_NFD)
        assertTrue(decoy.isDecoy)
        decoy.close()
        assertFalse(unlocked(APP_NFD).isDecoy)
    }

    @Test
    fun aRealPasswordADecoyPasswordAndAWrongOneCostTheSameDerivations() {
        // A vault from the old version holds the real slot under the raw text, and a decoy set now holds the NFC form. The typed text
        // opens the real one on its first key and the decoy on its second, so an early exit would tell them apart.
        create(ASCII)
        storeFromRawText(typed = ASCII, stored = ASCII, raw = APP_NFD)
        setDuress(APP_NFD, DURESS_NFC)

        assertEquals(2, derivationsOf(APP_NFD), "real")
        assertEquals(2, derivationsOf(DURESS_NFD), "decoy")
        assertEquals(2, derivationsOf(WRONG_NFD), "wrong")
        assertEquals(1, derivationsOf(DURESS_NFC), "a password that is NFC already derives once")
        assertEquals(1, derivationsOf(WRONG_NFC), "a wrong NFC password derives once")
    }

    @Test
    fun aRefusedAttemptDuringADelayCostsTheSameAsACheckedOne() {
        create(APP_NFC)

        for (typed in listOf(WRONG_NFD, WRONG_NFC)) {
            clock.advance(FailureDelay.LEARNING_CAP_MS)
            val checked = derivations { runBlocking { vault.unlock(typed.toCharArray()) } }
            assertTrue(runBlocking { vault.waitRemainingMs() } > 0L, "a wrong try starts a delay")

            val blocked = derivations { runBlocking { vault.unlock(typed.toCharArray()) } }

            assertEquals(checked, blocked, typed)
        }
    }

    @Test
    fun aChangedPasswordIsStoredFromItsNfcForm() {
        create(ASCII)
        val session = unlocked(ASCII)
        assertEquals(CreateResult.Created, runBlocking { vault.rekey(session, APP_NFD.toCharArray()) })
        session.close()

        unlocked(APP_NFC).close()
        unlocked(APP_NFD).close()
    }

    @Test
    fun aNewPasswordThatIsTheDecoysPasswordInAnotherFormIsRefused() {
        create(APP_NFC)
        setDuress(APP_NFC, DURESS_NFC)
        val real = unlocked(APP_NFC)

        assertEquals(CreateResult.PolicyRejected(PolicyReason.WEAK_SCORE), runBlocking { vault.rekey(real, DURESS_NFD.toCharArray()) })
        real.close()
        assertTrue(unlocked(DURESS_NFC).isDecoy)
    }

    @Test
    fun aDuressPasswordThatIsTheAppPasswordInAnotherFormIsRefused() {
        create(APP_NFC)
        val real = unlocked(APP_NFC)

        assertEquals(DuressResult.SameAsAppPassword, duress(real, APP_NFD))
        real.close()
    }

    @Test
    fun aDuressPasswordThatIsTheRawTextOfAnOldAppPasswordIsRefused() {
        create(ASCII)
        storeFromRawText(typed = ASCII, stored = ASCII, raw = APP_NFD)
        val real = unlocked(APP_NFD)

        // The raw NFD key opens the real slot, so the NFD text may not be set as the Duress Password, though its NFC form opens nothing.
        assertEquals(DuressResult.SameAsAppPassword, duress(real, APP_NFD))
        real.close()
    }

    @Test
    fun theFailureLimitsAcceptEitherFormOfTheAppPassword() {
        create(APP_NFC)

        assertEquals(FailureCapResult.Saved, runBlocking { vault.setFailureLimits(APP_NFD.toCharArray(), 30_000L) })
    }

    @Test
    fun theCodePointCountIsOfTheNfcForm() {
        // Eleven code points as NFC, twelve as NFD.
        val nfc = "Qx7!Rb9Tm4\u00e9"
        val nfd = JavaNormalizer.normalize(nfc, JavaNormalizer.Form.NFD)
        assertEquals(11, nfc.codePointCount(0, nfc.length))
        assertEquals(12, nfd.codePointCount(0, nfd.length))

        assertEquals(PolicyResult.Refused(PolicyReason.TOO_SHORT), PasswordPolicy.check(nfd.toCharArray()))
    }

    @Test
    fun sameTextComparesUpToUnicodeForm() {
        assertTrue(PasswordForm.sameText(APP_NFC.toCharArray(), APP_NFD.toCharArray()))
        assertTrue(PasswordForm.sameText(APP_NFD.toCharArray(), APP_NFC.toCharArray()))
        assertFalse(PasswordForm.sameText(APP_NFC.toCharArray(), WRONG_NFC.toCharArray()))
    }

    private fun create(password: String) {
        assertEquals(CreateResult.Created, runBlocking { vault.create(password.toCharArray()) })
    }

    private fun attempt(password: String): UnlockResult {
        clock.advance(FailureDelay.LEARNING_CAP_MS)
        return runBlocking { vault.unlock(password.toCharArray()) }
    }

    private fun unlocked(password: String): VaultSession = assertIs<UnlockResult.Unlocked>(attempt(password)).session

    /** The Argon2 derivations of one unlock of [password], after the delay has passed. */
    private fun derivationsOf(password: String): Int {
        clock.advance(FailureDelay.LEARNING_CAP_MS)
        return derivations { (runBlocking { vault.unlock(password.toCharArray()) } as? UnlockResult.Unlocked)?.session?.close() }
    }

    private fun derivations(unlock: () -> Unit): Int {
        kdfRuns.runs = 0
        unlock()
        return kdfRuns.runs
    }

    private fun duress(session: VaultSession, password: String): DuressResult =
        runBlocking { vault.setDuress(session, password.toCharArray()) { decoy -> decoy.createWallet() } }

    /** Sets the Duress Password from the real vault, which [realPassword] opens. */
    private fun setDuress(realPassword: String, password: String) {
        val real = unlocked(realPassword)
        assertEquals(DuressResult.Enabled, duress(real, password))
        real.close()
    }

    /** The Argon2 key of [text] as it is, with the slot key: what the vault derives for a slot, before any form is chosen. */
    private fun softKey(text: String, header: VaultHeader): ByteArray {
        val stretched = Argon2Kdf.derive(text.toCharArray(), header.salt, header.kdf)
        return hkdfSha256(stretched + keys.slotMac(header.salt), salt = null, info = SLOT_INFO, length = VaultKeyFile.KEY_BYTES)
    }

    /**
     * Seals the slot that [typed] opens again under the key of the raw text [raw], which is what 1.2.0 did with a password typed as [raw].
     * [stored] is the form the slot is sealed under now. The slot keeps its vault key, so the wallets stay.
     */
    private fun storeFromRawText(typed: String, stored: String, raw: String) {
        val session = unlocked(typed)
        val vaultKey = session.copyOfVaultKey()
        session.close()
        val header = VaultHeader.decode(layout.header.readBytes().copyOf(VaultHeader.FIELDS_BYTES))
        val contents = VaultKeyFile.decode(layout.vaultKey.readBytes())
        val index = VaultKeyFile.ownerIndex(contents, vaultKey)
        val hardwareWrapped = Aead.open(softKey(stored, header), VaultKeyFile.SOFTWARE_AAD, contents.sealedOf(index))
        val slot = VaultKeyFile.slotOf(vaultKey, Aead.seal(softKey(raw, header), VaultKeyFile.SOFTWARE_AAD, hardwareWrapped), SecureRandom())
        val slots = contents.slots.toMutableList().also { it[index] = slot }
        layout.vaultKey.writeBytes(VaultKeyFile.encode(slots, SecureRandom()))
    }

    private companion object {
        /** An NFC form and an NFD form of the same visible text, each with an accented letter. */
        const val APP_NFC = "Qx7!Rb9Tm4Wc8Zp-Caf\u00e9"
        const val APP_NFD = "Qx7!Rb9Tm4Wc8Zp-Cafe\u0301"
        const val DURESS_NFC = "Hj3\$kP9wQz!Lm6-Cr\u00e8me"
        const val DURESS_NFD = "Hj3\$kP9wQz!Lm6-Cre\u0300me"
        const val WRONG_NFC = "Zx8#Vb2!Nm5\$Qw7-Th\u00e9"
        const val WRONG_NFD = "Zx8#Vb2!Nm5\$Qw7-The\u0301"
        const val ASCII = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        val SLOT_INFO = "lensly-vault-slot-v$VAULT_FORMAT_VERSION".toByteArray(Charsets.US_ASCII)
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
