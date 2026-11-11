package app.lensly.core.vault

import android.content.pm.ApplicationInfo
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Backup Exclusion. The manifest sets allowBackup to false, and the two rule files exclude every app domain that holds the vault, for
 * cloud backup and for device transfer. A build that allows backup is refused before a vault is created. The manifest and the rules
 * are read from the module source, which is what gets packaged. The test runs with the module directory as its working directory.
 */
class BackupExclusionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val appDomains = setOf("root", "file", "database", "sharedpref")

    @Test
    fun theFlagReadsAllowBackupFromTheApplicationFlags() {
        assertTrue(BackupExclusion.allowsBackup(ApplicationInfo.FLAG_ALLOW_BACKUP))
        assertTrue(BackupExclusion.allowsBackup(ApplicationInfo.FLAG_ALLOW_BACKUP or ApplicationInfo.FLAG_DEBUGGABLE))
        assertFalse(BackupExclusion.allowsBackup(0))
        assertFalse(BackupExclusion.allowsBackup(ApplicationInfo.FLAG_DEBUGGABLE))
    }

    @Test
    fun thePackagedManifestSetsAllowBackupToFalseAndNamesBothRules() {
        val application = application(xml("src/main/AndroidManifest.xml"))

        assertEquals("false", application.getAttribute("android:allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules"))
        assertEquals("@xml/backup_rules", application.getAttribute("android:fullBackupContent"))
    }

    @Test
    fun cloudBackupAndDeviceTransferExcludeEveryAppDomain() {
        val rules = xml("src/main/res/xml/data_extraction_rules.xml").documentElement

        for (section in listOf("cloud-backup", "device-transfer")) {
            val scope = rules.getElementsByTagName(section).item(0) as Element
            assertTrue(excludedDomains(scope).containsAll(appDomains), "$section excludes ${excludedDomains(scope)}")
        }
    }

    @Test
    fun legacyFullBackupRulesExcludeEveryAppDomain() {
        val rules = xml("src/main/res/xml/backup_rules.xml").documentElement

        assertTrue(excludedDomains(rules).containsAll(appDomains), "full backup excludes ${excludedDomains(rules)}")
    }

    @Test
    fun aBuildThatAllowsBackupRefusesToCreateTheVault() {
        val layout = VaultLayout(File(tmp.root, "vault"))
        val vault = Vault(layout, MemoryKeystore(), FakeClock(), KDF, backupAllowed = true)

        assertEquals(BackupExclusion.REFUSAL, vault.refusal())
        assertFalse(vault.canCreate())
        assertEquals(CreateResult.BackupAllowed, runBlocking { vault.create(PASSWORD.toCharArray()) })
        assertFalse(vault.exists())
    }

    @Test
    fun aBuildThatExcludesBackupCreatesTheVault() {
        val layout = VaultLayout(File(tmp.root, "vault"))
        val vault = Vault(layout, MemoryKeystore(), FakeClock(), KDF, backupAllowed = false)

        assertNull(vault.refusal())
        assertTrue(vault.canCreate())
        assertEquals(CreateResult.Created, runBlocking { vault.create(PASSWORD.toCharArray()) })
        assertTrue(vault.exists())
    }

    /** The domains that a rule section excludes for the whole app, that is with path ".". */
    private fun excludedDomains(scope: Element): Set<String> {
        val excludes = scope.getElementsByTagName("exclude")
        return (0 until excludes.length)
            .map { excludes.item(it) as Element }
            .filter { it.getAttribute("path") == "." }
            .map { it.getAttribute("domain") }
            .toSet()
    }

    private fun application(manifest: Document): Element = manifest.getElementsByTagName("application").item(0) as Element

    private fun xml(path: String): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))

    private companion object {
        /** Passes [PasswordPolicy]. */
        const val PASSWORD = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
