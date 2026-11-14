package app.lensly.core.registry.update

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.Test

/** Paths are relative to :app, the working directory for unit tests. */
private const val MAIN = "src/main/assets"
private const val DEBUG = "src/debug/assets"

private fun fileReader(vararg roots: String): (String) -> String? = { path ->
    roots.map { File(it, path) }.firstOrNull { it.isFile }?.readText()
}

/** A release list holding [entries], which are JSON objects. */
private fun releaseList(vararg entries: String): (String) -> String? = { path ->
    if (path == TrustedKeys.RELEASE_PATH) """{"keys":[${entries.joinToString(",")}]}""" else null
}

private fun entry(id: String, publicKey: String, status: String = "active") =
    """{"id":"$id","publicKey":"$publicKey","status":"$status"}"""

private val TEST_HEX = TestPackages.PUBLIC_BYTES.joinToString("") { "%02x".format(it) }
private val TEST_ID = TrustedKey(TestPackages.PUBLIC_BYTES).id

class TrustedKeysTest {
    @Test
    fun theDevelopmentKeyIsListedOnlyInTheDebugSourceSet() {
        val release = TrustedKeys.load(fileReader(MAIN)).map { it.id }.toSet()
        val withDebug = TrustedKeys.load(fileReader(MAIN, DEBUG)).map { it.id }

        val developmentOnly = withDebug.filterNot { it in release }
        assertEquals(1, developmentOnly.size)
        assertFalse(release.contains(developmentOnly.single()))
    }

    @Test
    fun aRevokedEntryStaysListedWithItsStatus() {
        val keys = TrustedKeys.load(releaseList(entry(TEST_ID, TEST_HEX, "revoked")))

        assertEquals(listOf(TEST_ID), keys.map { it.id })
        assertEquals(KeyStatus.REVOKED, keys.single().status)
    }

    @Test
    fun anEntryWithAStatusOtherThanActiveOrRevokedIsRefused() {
        assertFailsWith<IllegalArgumentException> { TrustedKeys.load(releaseList(entry(TEST_ID, TEST_HEX, "retired"))) }
    }

    @Test
    fun anIdThatDoesNotBelongToItsPublicKeyIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            TrustedKeys.load(releaseList(entry("0000000000000000", TEST_HEX)))
        }
    }

    @Test
    fun aPublicKeyMustBe64HexadecimalCharacters() {
        assertFailsWith<IllegalArgumentException> { TrustedKeys.load(releaseList(entry(TEST_ID, "abcd"))) }
        assertFailsWith<IllegalArgumentException> { TrustedKeys.load(releaseList(entry(TEST_ID, "zz" + "0".repeat(62)))) }
    }

    @Test
    fun aKeyListedInBothFilesIsRefused() {
        val json = """{"keys":[${entry(TEST_ID, TEST_HEX)}]}"""
        val twice = { path: String ->
            when (path) {
                TrustedKeys.RELEASE_PATH, TrustedKeys.DEBUG_PATH -> json
                else -> null
            }
        }

        assertFailsWith<IllegalArgumentException> { TrustedKeys.load(twice) }
    }

    @Test
    fun missingReleaseListFailsLoudly() {
        assertFailsWith<IllegalStateException> { TrustedKeys.load { null } }
    }
}
