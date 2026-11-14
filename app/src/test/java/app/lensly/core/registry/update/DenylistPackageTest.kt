package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.ui.shippedRegistry
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val DENYLIST_ENTRY =
    """{"condition":"UNLIMITED","reason":"Test approval.","selector":"0x095ea7b3","severity":"WARN"}"""
private const val WALLET_ENTRY =
    """{"ecosystems":["EVM"],"formats":["ur:eth-sign-request"],"limitations":"Test limits.","name":"Test Wallet","note":"Test note."}"""
private val OTHER_SEED = ByteArray(32) { (it * 13 + 5).toByte() }

/** A package payload for the test key. [lists] are extra top-level members, each starting with a comma. */
private fun payload(sequence: Long = 2, lists: String = ""): String =
    """{"createdAt":"2026-11-14","formatVersion":2,"minSchemaVersion":2,"networks":[${TestPackages.LINEA_NETWORK}],""" +
        """"sequence":$sequence,"tokens":[${TestPackages.LINEA_USDC}]$lists}"""

/** The build's catalog with a whitelist sequence of [sequence], so a package can be older or newer than it. */
private fun bakedAtSequence(sequence: Long): Registry = RegistryLoader.load { path ->
    if (path == RegistryLoader.WHITELIST_PATH) {
        """{"sequence":$sequence,"whitelist":[]}"""
    } else {
        File("src/main/assets", path).takeIf(File::isFile)?.readText()
    }
}

/** The denylist and wallets that a package can replace, and the refusals of a package that carries them wrongly. */
class DenylistPackageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val baked = shippedRegistry()
    private val lists = ",\"denylist\":[$DENYLIST_ENTRY],\"wallets\":[$WALLET_ENTRY]"

    private fun dir(): File = folder.root.resolve("registry")

    private fun install(
        bytes: ByteArray,
        keys: List<TrustedKey> = TestPackages.TRUSTED,
        build: Registry = baked,
    ): Result<RegistryPackage> = RegistryUpdate.install(bytes, keys, build, dir())

    @Test
    fun aNewerPackageReplacesTheBuiltInDenylistAndWallets() {
        assertTrue(install(TestPackages.signed(payload(2, lists))).isSuccess)

        val effective = RegistryUpdate.withInstalled(baked, dir(), TestPackages.TRUSTED)

        assertEquals(listOf("0x095ea7b3"), effective.denylistEntries().map { it.selector })
        assertEquals(listOf("Test Wallet"), effective.walletEntries().map { it.name })
    }

    @Test
    fun aPackageWithAnEmptyDenylistClearsTheBuiltInOne() {
        assertTrue(install(TestPackages.signed(payload(2, ",\"denylist\":[]"))).isSuccess)

        val effective = RegistryUpdate.withInstalled(baked, dir(), TestPackages.TRUSTED)

        assertEquals(emptyList(), effective.denylistEntries())
        assertEquals(baked.walletEntries(), effective.walletEntries())
    }

    @Test
    fun aPackageThatOmitsTheListsKeepsTheBuiltInOnes() {
        assertTrue(install(TestPackages.signed(payload(2))).isSuccess)

        val effective = RegistryUpdate.withInstalled(baked, dir(), TestPackages.TRUSTED)

        assertEquals(baked.denylistEntries(), effective.denylistEntries())
        assertEquals(baked.walletEntries(), effective.walletEntries())
    }

    @Test
    fun aPackageNotNewerThanTheBuildLeavesItsListsAlone() {
        val build = bakedAtSequence(5)
        assertTrue(install(TestPackages.signed(payload(2, lists)), build = build).isSuccess)

        val effective = RegistryUpdate.withInstalled(build, dir(), TestPackages.TRUSTED)

        assertEquals(build.denylistEntries(), effective.denylistEntries())
        assertEquals(build.walletEntries(), effective.walletEntries())
        assertTrue(effective.network(4242L) != null, "networks still merge")
    }

    @Test
    fun aTamperedDenylistPackageIsRefusedAndNothingIsStored() {
        val bytes = TestPackages.signed(payload(2, lists))
        val at = String(bytes, StandardCharsets.ISO_8859_1).indexOf("Test approval.")
        bytes[at] = (bytes[at] + 1).toByte()

        assertIs<RegistryUpdateException.BadSignature>(install(bytes).exceptionOrNull())
        assertFalse(File(dir(), RegistryUpdate.INSTALLED_FILE).exists())
    }

    @Test
    fun anUnsignedDenylistPackageIsRefused() {
        val bytes = TestPackages.signed(payload(2, lists))
        bytes.fill(0, bytes.size - 64, bytes.size)

        assertIs<RegistryUpdateException.BadSignature>(install(bytes).exceptionOrNull())
    }

    @Test
    fun aDenylistFromARevokedKeyIsRefusedByName() {
        val revoked = listOf(TrustedKey(TestPackages.PUBLIC_BYTES, KeyStatus.REVOKED))

        val refused = assertIs<RegistryUpdateException.RevokedKey>(
            install(TestPackages.signed(payload(2, lists)), keys = revoked).exceptionOrNull(),
        )

        assertEquals(TestPackages.TRUSTED.single().id, refused.keyId)
    }

    @Test
    fun aDenylistFromAnUnknownKeyIsRefused() {
        val bytes = TestPackages.signed(payload(2, lists), seed = OTHER_SEED)

        assertIs<RegistryUpdateException.UnknownKey>(install(bytes).exceptionOrNull())
    }

    @Test
    fun aDenylistEntryNamingAShortSelectorRefusesThePackage() {
        val short = ",\"denylist\":[{\"condition\":\"ANY\",\"reason\":\"Short.\",\"selector\":\"0x095ea7b\",\"severity\":\"WARN\"}]"

        assertIs<RegistryUpdateException.InvalidEntry>(install(TestPackages.signed(payload(2, short))).exceptionOrNull())
    }

    @Test
    fun aWalletWithoutANoteRefusesThePackage() {
        val blank = ",\"wallets\":[{\"ecosystems\":[\"EVM\"],\"formats\":[\"ur:eth-sign-request\"],\"limitations\":\"x\"," +
            "\"name\":\"Test Wallet\",\"note\":\" \"}]"

        assertIs<RegistryUpdateException.InvalidEntry>(install(TestPackages.signed(payload(2, blank))).exceptionOrNull())
    }

    @Test
    fun anUnknownSeverityRefusesThePackageAsMalformed() {
        val unknown = ",\"denylist\":[{\"condition\":\"ANY\",\"reason\":\"x\",\"selector\":\"0x095ea7b3\",\"severity\":\"MAYBE\"}]"

        assertIs<RegistryUpdateException.Malformed>(install(TestPackages.signed(payload(2, unknown))).exceptionOrNull())
    }
}
