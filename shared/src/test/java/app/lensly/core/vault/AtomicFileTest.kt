package app.lensly.core.vault

import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tempFilesIn(dir: File): List<String> =
        dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".tmp") }

    @Test
    fun writeAtomicallyReplacesTheTargetAndLeavesNoTempFile() {
        val dir = tmp.newFolder("vault")
        val target = File(dir, "vault.key")
        target.writeBytes(byteArrayOf(1))

        writeAtomically(target, byteArrayOf(2, 3))

        assertTrue(target.readBytes().contentEquals(byteArrayOf(2, 3)))
        assertEquals(emptyList(), tempFilesIn(dir))
    }

    @Test
    fun aStaleTempFileIsNeitherUsedNorOverwritten() {
        val dir = tmp.newFolder("vault")
        val target = File(dir, "vault.key")
        val stale = File(dir, "vault.key.tmp")
        stale.writeText("stale")

        writeAtomically(target, byteArrayOf(2, 3))

        assertTrue(target.readBytes().contentEquals(byteArrayOf(2, 3)))
        assertEquals("stale", stale.readText(), "the fixed-name file is left alone")
    }

    @Test
    fun aFailedCommitDeletesItsTempFile() {
        val dir = tmp.newFolder("vault")
        // A directory at the target makes the rename fail, so the commit throws after the temp file is written.
        val target = File(dir, "failures.bak")
        target.mkdirs()

        assertFailsWith<IOException> { writeAtomically(target, byteArrayOf(2, 3)) }

        assertEquals(emptyList(), tempFilesIn(dir))
    }
}
