package app.lensly.core.vault

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * [VaultRecordNames.KNOWN] decides which record files survive a Duress purge and which a wipe deletes, so a record name that is
 * missing there is lost or left behind. This test reads the app sources instead of repeating their names, so a new record name
 * cannot be forgotten.
 */
class VaultRecordNamesTest {

    @Test
    fun everyRecordNameTheAppsWriteIsKnown() {
        val root = File(System.getProperty("user.dir")).parentFile
        val sources = listOf("shared", "app", "lumio")
            .map { File(root, "$it/src/main") }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .map { it.readText() }
            .filter { "VaultRecord(" in it }
        val names = sources.flatMap { text -> RECORD_CONSTANT.findAll(text).map { it.groupValues[1] }.toList() }.toSet()

        // Lensly and Lumio keep well over a dozen records, so a short list means the scan saw too little.
        assertTrue(names.size > 12, "found only $names")
        assertEquals(emptySet(), names - VaultRecordNames.KNOWN.toSet(), "record names missing from VaultRecordNames.KNOWN")
    }

    private companion object {
        /** A store's record name, as a `RECORD` or `NAME` constant. */
        val RECORD_CONSTANT = Regex("""const val (?:RECORD|NAME) = "([^"]+)"""")
    }
}
