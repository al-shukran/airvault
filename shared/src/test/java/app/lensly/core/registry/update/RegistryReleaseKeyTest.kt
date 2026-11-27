package app.lensly.core.registry.update

import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/** The key ID of the offline backup release key, as the release list pins it. */
private const val BACKUP_KEY_ID = "8f0627c7e94d8174"

/**
 * The pinned release signing key and the bundled package it signs. Paths are relative to :shared, the working directory for
 * unit tests. The release configuration reads only the release list, as a release build does; the debug list is read only in
 * the debug source set.
 */
class RegistryReleaseKeyTest {
    private val releaseOnly: (String) -> String? = { path -> File(MAIN, path).takeIf { it.isFile }?.readText() }
    private val releaseKeys: List<TrustedKey> = TrustedKeys.load(releaseOnly)
    private val baked: Registry = RegistryLoader.load(releaseOnly)
    private val bundled: ByteArray = File(MAIN, BUNDLED).readBytes()

    @Test
    fun theBundledPackageVerifiesWithThePinnedReleaseKey() {
        val pkg = RegistryUpdate.verify(bundled, releaseKeys, baked).getOrThrow()

        assertEquals(10, pkg.whitelist?.size)
    }

    @Test
    fun theBundledPackageNamesAnActivePinnedReleaseKeyInItsHeader() {
        val named = bundled.copyOfRange(KEY_ID_OFFSET, KEY_ID_OFFSET + TrustedKey.ID_BYTES)
        val pinned = releaseKeys.single { it.id == TrustedKey.hex(named) }

        assertEquals(KeyStatus.ACTIVE, pinned.status)
    }

    /** The offline backup key, pinned beside the release key so that losing the release key does not end catalog updates. */
    @Test
    fun aBackupKeyIsPinnedAsASecondActiveReleaseKey() {
        val backup = releaseKeys.single { it.id == BACKUP_KEY_ID }

        assertEquals(KeyStatus.ACTIVE, backup.status)
        assertEquals(2, releaseKeys.count { it.status == KeyStatus.ACTIVE })
    }

    @Test
    fun aChangedByteInThePayloadIsRejectedAsABadSignature() {
        val tampered = bundled.copyOf().also { it[PAYLOAD_OFFSET + 40] = (it[PAYLOAD_OFFSET + 40].toInt() xor 1).toByte() }

        assertIs<RegistryUpdateException.BadSignature>(RegistryUpdate.verify(tampered, releaseKeys, baked).exceptionOrNull())
    }

    @Test
    fun aChangedByteInTheSignatureIsRejectedAsABadSignature() {
        val tampered = bundled.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

        assertIs<RegistryUpdateException.BadSignature>(RegistryUpdate.verify(tampered, releaseKeys, baked).exceptionOrNull())
    }

    @Test
    fun aPayloadSignedWithTheTestKeyIsRejectedByTheReleaseConfiguration() {
        val signedWithTestKey = TestPackages.signed(TestPackages.payload(sequence = 2))

        assertIs<RegistryUpdateException.UnknownKey>(
            RegistryUpdate.verify(signedWithTestKey, releaseKeys, baked).exceptionOrNull(),
        )
    }

    @Test
    fun thePublishedDevelopmentSampleIsSignedByTheDebugKeyOnly() {
        val sample = File(SAMPLE).readBytes()
        val debugKeys = TrustedKeys.load { path ->
            File(MAIN, path).takeIf { it.isFile }?.readText() ?: File(DEBUG, path).takeIf { it.isFile }?.readText()
        }

        assertIs<RegistryUpdateException.UnknownKey>(RegistryUpdate.verify(sample, releaseKeys, baked).exceptionOrNull())
        // The debug key is recognised: whatever refuses the sample under the debug catalog, it is not its signature.
        val debugResult = RegistryUpdate.verify(sample, debugKeys, baked).exceptionOrNull()
        assertTrue(
            debugResult !is RegistryUpdateException.UnknownKey && debugResult !is RegistryUpdateException.BadSignature,
            "the debug key must be the one that signed the sample, but got $debugResult",
        )
    }

    private companion object {
        const val MAIN = "src/main/assets"
        const val DEBUG = "src/debug/assets"
        const val BUNDLED = "registry/bundled.lrp"
        const val SAMPLE = "../tools/registry-pack/samples/example.lrp"
        const val KEY_ID_OFFSET = 8
        const val PAYLOAD_OFFSET = 16
    }
}
