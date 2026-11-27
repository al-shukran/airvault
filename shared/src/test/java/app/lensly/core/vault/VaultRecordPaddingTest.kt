package app.lensly.core.vault

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
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

    // 1596: the times and inodes of the folder.

    private class Stamp(val modified: FileTime, val created: FileTime)

    private fun stamps(): Map<String, Stamp> = layout.vaultRecords.listFiles().orEmpty().associate {
        val a = Files.readAttributes(it.toPath(), BasicFileAttributes::class.java)
        it.name to Stamp(a.lastModifiedTime(), a.creationTime())
    }

    /**
     * Every file shares one modification time, and with [creationIsFresh] every creation time is newer than any before. A claim or a retire
     * gives files new names, so files are not told apart by name. Inode numbers are not compared: a file system may reuse a freed one.
     */
    private fun assertFolderLooksUniform(before: Map<String, Stamp>, creationIsFresh: Boolean = false) {
        val after = stamps()
        assertEquals(before.size, after.size)
        assertEquals(1, after.values.map { it.modified }.toSet().size, "Modification times differ: ${after.values.map { it.modified }}")
        val newestBefore = before.values.maxOf { it.created }
        // Some file systems and the JDK keep creation times in whole seconds, so only a test that waited that long can check them.
        if (creationIsFresh) after.forEach { (name, now) -> assertTrue(now.created > newestBefore, "$name keeps its creation time") }
    }

    @Test
    fun everyRecordFileHasTheSameTimesAfterTheRealVaultAndTheDecoyVaultWriteRecords() {
        create()
        assertEquals(1, stamps().values.map { it.modified }.toSet().size, "A new vault shows different modification times")

        val real = unlocked(APP)
        Thread.sleep(1100)
        var before = stamps()
        real.putVaultRecord("settings", byteArrayOf(1, 2, 3))
        assertFolderLooksUniform(before, creationIsFresh = true)

        Thread.sleep(50)
        before = stamps()
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(real, DURESS.toCharArray()) { it.createWallet() } })
        real.close()
        assertFolderLooksUniform(before)

        val decoy = unlocked(DURESS)
        Thread.sleep(50)
        before = stamps()
        decoy.putVaultRecord("settings", byteArrayOf(4, 5, 6))
        assertFolderLooksUniform(before)

        // Wiping the Decoy Vault retires its files and resets them.
        Thread.sleep(50)
        before = stamps()
        vault.wipeDecoy(decoy)
        decoy.close()
        assertFolderLooksUniform(before)
    }

    @Test
    fun aWriteLeavesTheValuesOfTheOtherRecordsAndTheDecoyVaultIntact() {
        create()
        val real = unlocked(APP)
        real.putVaultRecord("settings", byteArrayOf(1))
        real.putVaultRecord("address-book", byteArrayOf(2))
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(real, DURESS.toCharArray()) { it.createWallet() } })
        real.close()
        val decoy = unlocked(DURESS)
        decoy.putVaultRecord("settings", byteArrayOf(9))
        decoy.close()

        val again = unlocked(APP)
        assertContentEquals(byteArrayOf(1), again.getVaultRecord("settings"))
        assertContentEquals(byteArrayOf(2), again.getVaultRecord("address-book"))
        again.close()
        val decoyAgain = unlocked(DURESS)
        assertContentEquals(byteArrayOf(9), decoyAgain.getVaultRecord("settings"))
        decoyAgain.close()
    }

    @Test
    fun aWipeOfTheDecoyVaultWaitsForARunningPassSoThePassCannotPutBackAStaleCopy() {
        create()
        val real = unlocked(APP)
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(real, DURESS.toCharArray()) { it.createWallet() } })
        real.close()
        val decoy = unlocked(DURESS)
        decoy.putVaultRecord("settings", byteArrayOf(9))
        val stale = decoy.vaultRecordFileForTesting("settings")

        // A pass has read the decoy's settings file and is about to write it back when the wipe resets that file.
        val inPass = java.util.concurrent.CountDownLatch(1)
        VaultRecordNames.passHookForTesting = { file ->
            // Only the first pass stalls: the wipe's own pass must run through.
            if (file == stale && inPass.count > 0) {
                inPass.countDown()
                Thread.sleep(400)
            }
        }
        try {
            val writer = Thread { decoy.putVaultRecord("address-book", byteArrayOf(1)) }
            writer.start()
            assertTrue(inPass.await(10, java.util.concurrent.TimeUnit.SECONDS))
            vault.wipeDecoy(decoy)
            writer.join()
        } finally {
            VaultRecordNames.passHookForTesting = null
        }
        decoy.close()

        val again = unlocked(DURESS)
        assertNull(again.getVaultRecord("settings"), "A pass put the wiped record back")
        again.close()
    }

    @Test
    fun aPassThatFailsAfterTheRecordIsSavedDoesNotFailTheWrite() {
        create()
        val real = unlocked(APP)
        val other = layout.vaultRecords.listFiles().orEmpty().first { it != real.vaultRecordFileForTesting("settings") }
        other.setReadable(false)
        try {
            org.junit.Assume.assumeFalse("The file is still readable (running as root)", other.canRead())
            real.putVaultRecord("settings", byteArrayOf(5))
            assertContentEquals(byteArrayOf(5), real.getVaultRecord("settings"))
        } finally {
            other.setReadable(true)
        }
        real.close()
    }

    @Test
    fun aPassRemovesTempFilesThatACrashLeft() {
        create()
        val real = unlocked(APP)
        val leftover = File(layout.vaultRecords, "${real.vaultRecordFileForTesting("settings").name}.123.tmp")
        leftover.writeBytes(ByteArray(1024))
        val names = layout.vaultRecords.listFiles().orEmpty().map { it.name }.toSet()

        real.putVaultRecord("settings", byteArrayOf(1))

        assertFalse(leftover.exists())
        assertEquals(names - leftover.name, layout.vaultRecords.listFiles().orEmpty().map { it.name }.toSet())
        real.close()
    }

    @Test
    fun aPassOverTheWholeFolderIsFast() {
        create()
        val real = unlocked(APP)
        real.putVaultRecord("settings", byteArrayOf(1))
        val started = System.nanoTime()
        repeat(20) { real.putVaultRecord("settings", byteArrayOf(it.toByte())) }
        val millis = (System.nanoTime() - started) / 20 / 1_000_000
        println("record write incl. whole-folder pass: $millis ms, ${layout.vaultRecords.listFiles()!!.size} files, ${sizes().sum()} bytes")
        assertTrue(millis < 1000, "A record write takes $millis ms")
        real.close()
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
