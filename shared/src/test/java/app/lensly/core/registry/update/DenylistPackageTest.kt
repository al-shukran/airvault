package app.lensly.core.registry.update

import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.DenylistSeverity
import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.vault.MemoryKeystore
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
    """{"createdAt":"2026-11-14","formatVersion":2,"minSchemaVersion":4,"networks":[${TestPackages.LINEA_NETWORK}],""" +
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
    private val keystore = MemoryKeystore()
    private val lists = ",\"denylist\":[$DENYLIST_ENTRY],\"wallets\":[$WALLET_ENTRY]"

    private fun dir(): File = folder.root.resolve("registry")

    private fun install(
        bytes: ByteArray,
        keys: List<TrustedKey> = TestPackages.TRUSTED,
        build: Registry = baked,
    ): Result<RegistryPackage> = RegistryUpdate.install(bytes, keys, build, dir(), keystore)

    @Test
    fun aNewerPackageReplacesTheBuiltInDenylistAndWallets() {
        assertTrue(install(TestPackages.signed(payload(2, lists))).isSuccess)

        val effective = RegistryUpdate.withInstalled(baked, dir(), TestPackages.TRUSTED, keystore)

        assertEquals(listOf("0x095ea7b3"), effective.denylistEntries().map { it.selector })
        assertEquals(listOf("Test Wallet"), effective.walletEntries().map { it.name })
    }

    @Test
    fun aPackageWithAnEmptyDenylistClearsTheBuiltInOne() {
        assertTrue(install(TestPackages.signed(payload(2, ",\"denylist\":[]"))).isSuccess)

        val effective = RegistryUpdate.withInstalled(baked, dir(), TestPackages.TRUSTED, keystore)

        assertEquals(emptyList(), effective.denylistEntries())
        assertEquals(baked.walletEntries(), effective.walletEntries())
    }

    @Test
    fun aPackageThatOmitsTheListsKeepsTheBuiltInOnes() {
        assertTrue(install(TestPackages.signed(payload(2))).isSuccess)

        val effective = RegistryUpdate.withInstalled(baked, dir(), TestPackages.TRUSTED, keystore)

        assertEquals(baked.denylistEntries(), effective.denylistEntries())
        assertEquals(baked.walletEntries(), effective.walletEntries())
    }

    @Test
    fun aPackageNotNewerThanTheBuildLeavesItsListsAlone() {
        val build = bakedAtSequence(5)
        assertTrue(install(TestPackages.signed(payload(2, lists)), build = build).isSuccess)

        val effective = RegistryUpdate.withInstalled(build, dir(), TestPackages.TRUSTED, keystore)

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

    @Test
    fun aDenylistPartyNeedsSchemaFiveAndIsReadAsTheEntrysCounterparty() {
        val party = "0x6666666666666666666666666666666666666666"
        val entry = """{"condition":"ANY","party":"$party","reason":"Known drainer.","severity":"BLOCK"}"""
        val withParty = { schema: Int ->
            TestPackages.signed(payload(2, ",\"denylist\":[$entry]").replace("\"minSchemaVersion\":4", "\"minSchemaVersion\":$schema"))
        }

        assertIs<RegistryUpdateException.Malformed>(install(withParty(4)).exceptionOrNull())
        assertTrue(install(withParty(5)).isSuccess)
        assertEquals(listOf(party), RegistryUpdate.withInstalled(baked, dir(), TestPackages.TRUSTED, keystore).denylistEntries().map { it.party })
    }

    @Test
    fun aDenylistPartyThatIsNotALowercaseAddressIsRefused() {
        val entry = """{"condition":"ANY","party":"0xABC","reason":"Bad.","severity":"BLOCK"}"""
        val bytes = TestPackages.signed(payload(2, ",\"denylist\":[$entry]").replace("\"minSchemaVersion\":4", "\"minSchemaVersion\":5"))

        assertIs<RegistryUpdateException.InvalidEntry>(install(bytes).exceptionOrNull())
    }

    private fun withDenylist(sequence: Long, reason: String?): ByteArray {
        val entry = """{"condition":"ANY","reason":"$reason","selector":"0xf2fde38b","severity":"WARN"}"""
        return TestPackages.signed(payload(sequence, reason?.let { ",\"denylist\":[$entry]" }.orEmpty()))
    }

    private fun denylistReasons(): List<String> =
        RegistryUpdate.withInstalled(baked, dir(), TestPackages.TRUSTED, keystore).denylistEntries().map { it.reason }

    @Test
    fun aLaterPackageThatLeavesOutItsDenylistKeepsTheLastInstalledOne() {
        install(withDenylist(5, "Signed list.")).getOrThrow()
        install(withDenylist(6, null)).getOrThrow()

        // The signed entry replaces the built-in one for its selector and the other built-in entries stay (see the union test below).
        val builtIn = baked.denylistEntries().filter { it.selector != "0xf2fde38b" }.map { it.reason }
        assertEquals(listOf("Signed list.") + builtIn, denylistReasons())
        assertFalse(RegistryUpdate.withInstalled(baked, dir(), TestPackages.TRUSTED, keystore).installedUpdateUnverified)
        // Control: a package that restates another denylist replaces it.
        install(withDenylist(7, "Restated list.")).getOrThrow()
        assertEquals(listOf("Restated list."), denylistReasons())
    }

    @Test
    fun aReplacedDenylistFileIsNotKeptAndTheStoreIsFlagged() {
        install(withDenylist(5, "Signed list.")).getOrThrow()
        install(withDenylist(6, null)).getOrThrow()
        File(dir(), RegistryUpdate.DENYLIST_FILE).writeBytes(withDenylist(3, "Older list."))

        assertEquals(baked.denylistEntries().map { it.reason }, denylistReasons())
        assertTrue(RegistryUpdate.withInstalled(baked, dir(), TestPackages.TRUSTED, keystore).installedUpdateUnverified)
    }

    @Test
    fun aDeletedDenylistFileFlagsTheStoreInsteadOfFallingBackQuietly() {
        install(withDenylist(5, "Signed list.")).getOrThrow()
        install(withDenylist(6, null)).getOrThrow()
        assertTrue(File(dir(), RegistryUpdate.DENYLIST_FILE).delete())

        val effective = RegistryUpdate.withInstalled(baked, dir(), TestPackages.TRUSTED, keystore)

        assertTrue(effective.installedUpdateUnverified)
        assertEquals(baked.denylistEntries(), effective.denylistEntries())
    }

    @Test
    fun aKeptDenylistKeepsTheBuiltInEntriesItDoesNotCoverAndWinsWhereItDoes() {
        install(TestPackages.signed(payload(5, lists))).getOrThrow()
        install(withDenylist(6, null)).getOrThrow()

        val entries = RegistryUpdate.withInstalled(baked, dir(), TestPackages.TRUSTED, keystore).denylistEntries()

        // The signed entry has the same target as the first built-in one and replaces it; the other built-in entries stay.
        assertEquals("Test approval.", entries.single { it.selector == "0x095ea7b3" }.reason)
        assertEquals(baked.denylistEntries().size, entries.size)
        assertTrue(baked.denylistEntries().drop(1).all { it in entries })
    }

    @Test
    fun aKeptEntryForOnePartyDoesNotDropTheBuiltInEntryForAnotherParty() {
        val partyA = "0x6666666666666666666666666666666666666666"
        val partyB = "0x7777777777777777777777777777777777777777"
        val builtIn = DenylistEntry(party = partyB, severity = DenylistSeverity.BLOCK, reason = "Built-in drainer.")
        val build = baked.withDenylist(listOf(builtIn))
        val entry = """{"condition":"ANY","party":"$partyA","reason":"Signed drainer.","severity":"BLOCK"}"""
        val withParty = { sequence: Long, list: String ->
            TestPackages.signed(payload(sequence, list).replace("\"minSchemaVersion\":4", "\"minSchemaVersion\":5"))
        }
        install(withParty(5, ",\"denylist\":[$entry]"), build = build).getOrThrow()
        install(withParty(6, ""), build = build).getOrThrow()

        val entries = RegistryUpdate.withInstalled(build, dir(), TestPackages.TRUSTED, keystore).denylistEntries()

        assertEquals(listOf(partyA, partyB), entries.map { it.party })
    }

    /** A store as Lensly 1.2.0 wrote it: its installed package carries the denylist, and no seal or Keystore key exists. */
    private fun write1_2_0StoreWithDenylist() {
        dir().mkdirs()
        File(dir(), RegistryUpdate.INSTALLED_FILE).writeBytes(withDenylist(5, "Signed list."))
        File(dir(), RegistryUpdate.LEGACY_SEQUENCE_FILE).writeText("5")
    }

    @Test
    fun anAdoptedStoreKeepsTheDenylistOfItsInstalledPackageThroughALaterPackageWithoutOne() {
        write1_2_0StoreWithDenylist()

        install(withDenylist(6, null)).getOrThrow()

        val builtIn = baked.denylistEntries().filter { it.selector != "0xf2fde38b" }.map { it.reason }
        assertEquals(listOf("Signed list.") + builtIn, denylistReasons())
        assertFalse(RegistryUpdate.withInstalled(baked, dir(), TestPackages.TRUSTED, keystore).installedUpdateUnverified)
    }

    @Test
    fun anAdoptedStoreFlagsALostDenylistFileInsteadOfFallingBackQuietly() {
        write1_2_0StoreWithDenylist()
        install(withDenylist(6, null)).getOrThrow()
        assertTrue(File(dir(), RegistryUpdate.DENYLIST_FILE).delete())

        assertTrue(RegistryUpdate.withInstalled(baked, dir(), TestPackages.TRUSTED, keystore).installedUpdateUnverified)
    }
}
