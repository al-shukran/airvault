package app.lensly.core.vault

import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Vault format 5: every file in `records/` is one size class long, a placeholder, a spare and a written record alike, so the sizes of
 * the folder say neither which records hold a value nor that a Decoy Vault wrote any.
 */
class VaultRecordPaddingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()
    private val layout by lazy { VaultLayout(File(tmp.root, "vault")) }
    private val vault by lazy {
        Vault(layout, keys, FakeClock(), KDF, readBootCount = { null }, onErased = {})
    }

    private fun create() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    private fun unlocked(password: String): VaultSession {
        val result = runBlocking { vault.unlock(password.toCharArray()) }
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return result.session
    }

    private fun sizes(): List<Long> = layout.vaultRecords.listFiles().orEmpty().map { it.length() }.sorted()

    private fun writeAll(session: VaultSession) {
        VaultRecordNames.KNOWN.forEach { session.putVaultRecord(it, byteArrayOf(1, 2, 3)) }
    }

    // 1557: the sizes of the folder.

    @Test
    fun theSizesOfTheRecordsFolderNeverChangeWhenTheRealVaultAndTheDecoyVaultWriteRecords() {
        create()
        val before = sizes()

        val real = unlocked(APP)
        writeAll(real)
        assertEquals(before, sizes())

        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(real, DURESS.toCharArray()) { it.createWallet() } })
        real.close()
        assertEquals(before, sizes())

        val decoy = unlocked(DURESS)
        writeAll(decoy)
        decoy.close()
        assertEquals(before, sizes())

    }

    @Test
    fun everyRecordFileIsOneSizeClassLongWhetherPlaceholderSpareOrWritten() {
        create()
        val classes = setOf(256L, 1024L, 4096L, 16384L)
        assertTrue(sizes().all { it in classes }, "A placeholder or spare is not a size class: ${sizes()}")
        // A record that holds a value keeps the size of its placeholder.
        val real = unlocked(APP)
        writeAll(real)
        real.close()
        assertTrue(sizes().all { it in classes }, "A written record is not a size class: ${sizes()}")
    }

    @Test
    fun aRecordThatOutgrowsItsClassMovesUpAndKeepsItsNewSize() {
        create()
        val real = unlocked(APP)
        val file = real.vaultRecordFileForTesting("settings")
        assertEquals(1024L, file.length())

        val big = ByteArray(3000) { 7 }
        real.putVaultRecord("settings", big)
        assertEquals(4096L, file.length())
        assertContentEquals(big, real.getVaultRecord("settings"))

        // A shorter value fits the larger file, and the file does not shrink, so its size does not show the value is gone.
        real.putVaultRecord("settings", byteArrayOf(1))
        assertEquals(4096L, file.length())
        assertContentEquals(byteArrayOf(1), real.getVaultRecord("settings"))
        real.close()
    }

    @Test
    fun aRecordLargerThanTheTopClassRoundsUpToAMultipleOfIt() {
        create()
        val real = unlocked(APP)
        val value = ByteArray(40_000) { 5 }
        real.putVaultRecord("settings", value)

        assertEquals(3 * 16384L, real.vaultRecordFileForTesting("settings").length())
        assertContentEquals(value, real.getVaultRecord("settings"))
        real.close()
    }

    @Test
    fun aDecoyRecordTakesAFileOfTheClassOfItsName() {
        create()
        val real = unlocked(APP)
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(real, DURESS.toCharArray()) { it.createWallet() } })
        real.close()

        val decoy = unlocked(DURESS)
        VaultRecordNames.KNOWN.forEach { name ->
            assertEquals(
                VaultRecordNames.fileBytesOf(name).toLong(),
                decoy.vaultRecordFileForTesting(name).length(),
                "The decoy file of $name is in another class than the real one.",
            )
        }
        decoy.close()
    }

    @Test
    fun aVersion4VaultIsErasedLikeAnyOlderOne() {
        create()
        val bytes = layout.vaultKey.readBytes().also { it[4] = 4 }
        layout.vaultKey.writeBytes(bytes)
        val store = FailureStore(layout.root, keys)
        store.recordKeyFile(store.load(FakeClock()), VaultKeyFile.digestOf(bytes), FakeClock())

        assertEquals(UnlockResult.Erased, runBlocking { vault.unlock(APP.toCharArray()) })
        assertFalse(layout.exists())
    }

    // The padding is part of the sealed record.

    @Test
    fun sizeClassesRoundUp() {
        assertEquals(256, VaultRecordNames.classFor(0))
        assertEquals(256, VaultRecordNames.classFor(256))
        assertEquals(1024, VaultRecordNames.classFor(257))
        assertEquals(4096, VaultRecordNames.classFor(4096))
        assertEquals(16384, VaultRecordNames.classFor(4097))
        assertEquals(16384, VaultRecordNames.classFor(16384))
        assertEquals(32768, VaultRecordNames.classFor(16385))
        assertEquals(49152, VaultRecordNames.classFor(40_000))
    }

    @Test
    fun aPaddedRecordReadsBackItsValueAndAnUnsetOneReadsNull() {
        val plain = plainOf(VaultRecordNames.sealed(KEY, "settings", byteArrayOf(9, 8, 7)))
        assertContentEquals(byteArrayOf(9, 8, 7), VaultRecordNames.valueOf(plain))
        assertContentEquals(ByteArray(0), VaultRecordNames.valueOf(plainOf(VaultRecordNames.sealed(KEY, "settings", ByteArray(0)))))
        assertNull(VaultRecordNames.valueOf(plainOf(VaultRecordNames.sealedUnset(KEY, "settings"))))
    }

    @Test
    fun paddingThatIsNotZeroIsRejected() {
        val plain = plainOf(VaultRecordNames.sealed(KEY, "settings", byteArrayOf(1)))
        plain[plain.size - 1] = 1
        assertFailsWith<VaultException> { VaultRecordNames.valueOf(plain) }
        val inside = plainOf(VaultRecordNames.sealed(KEY, "settings", byteArrayOf(1)))
        inside[6] = 1
        assertFailsWith<VaultException> { VaultRecordNames.valueOf(inside) }
    }

    @Test
    fun aLengthThatReachesPastTheRecordOrIsNegativeIsRejected() {
        for (length in intArrayOf(Int.MIN_VALUE, -1, 1024)) {
            val plain = plainOf(VaultRecordNames.sealed(KEY, "settings", byteArrayOf(1)))
            java.nio.ByteBuffer.wrap(plain, 1, 4).putInt(length)
            assertFailsWith<VaultException> { VaultRecordNames.valueOf(plain) }
        }
    }

    @Test
    fun anUnknownFlagOrAnUnsetRecordWithALengthIsRejected() {
        val flag = plainOf(VaultRecordNames.sealed(KEY, "settings", byteArrayOf(1))).also { it[0] = 2 }
        assertFailsWith<VaultException> { VaultRecordNames.valueOf(flag) }
        val unset = plainOf(VaultRecordNames.sealedUnset(KEY, "settings")).also { it[4] = 1 }
        assertFailsWith<VaultException> { VaultRecordNames.valueOf(unset) }
    }

    @Test
    fun aRecordThatIsNotASizeClassLongIsRejected() {
        val plain = plainOf(VaultRecordNames.sealed(KEY, "settings", byteArrayOf(1)))
        assertFailsWith<VaultException> { VaultRecordNames.valueOf(plain.copyOf(plain.size - 1)) }
        assertFailsWith<VaultException> { VaultRecordNames.valueOf(plain.copyOf(plain.size + 1)) }
        assertFailsWith<VaultException> { VaultRecordNames.valueOf(ByteArray(2)) }
    }

    @Test
    fun aRecordFileWithItsPaddingStrippedDoesNotAuthenticate() {
        create()
        val real = unlocked(APP)
        real.putVaultRecord("settings", byteArrayOf(1, 2, 3))
        val file = real.vaultRecordFileForTesting("settings")
        file.writeBytes(file.readBytes().copyOf(256))

        assertFailsWith<VaultException> { real.getVaultRecord("settings") }
        real.close()
    }

    private fun plainOf(file: ByteArray): ByteArray = Aead.open(KEY, VaultRecordNames.aad("settings"), decodeBlob(file))

    private companion object {
        const val APP = "TEST_ONLY_q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "TEST_ONLY_Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
        val KEY = ByteArray(32) { (it * 5 + 2).toByte() }
    }
}
