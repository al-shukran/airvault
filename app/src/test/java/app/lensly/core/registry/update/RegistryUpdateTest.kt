package app.lensly.core.registry.update

import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Paths are relative to :app, the working directory for unit tests. */
private const val MAIN = "src/main/assets"
private const val DEBUG = "src/debug/assets"
private const val SAMPLE = "../tools/registry-pack/samples/example.lrp"
private const val USDC_ETHEREUM = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
private const val USDC_LINEA = "0x176211869ca2b568f2a7d4ee941e073a821ee1ff"

/** Simulates the APK layout: each path is read from the first root that contains it. */
private fun fileReader(vararg roots: String): (String) -> String? = { path ->
    roots.map { File(it, path) }.firstOrNull { it.isFile }?.readText()
}

private fun fakeReader(vararg files: Pair<String, String>): (String) -> String? {
    val byPath = files.toMap()
    return { path -> byPath[path] }
}

private fun bakedRegistry(): Registry = RegistryLoader.load(fileReader(MAIN))

private fun verifyBytes(
    bytes: ByteArray,
    keys: List<TrustedKey> = TestPackages.TRUSTED,
    baked: Registry = bakedRegistry(),
): Result<RegistryPackage> = RegistryUpdate.verify(bytes, keys, baked)

private fun verifyPayload(payload: String, baked: Registry = bakedRegistry()): Result<RegistryPackage> =
    verifyBytes(TestPackages.signed(payload), baked = baked)

private fun failure(result: Result<*>): Throwable = assertNotNull(result.exceptionOrNull())

/** The same package bytes with another magic: the format is checked before the signature, so the signature need not match. */
private fun withMagic(bytes: ByteArray, magic: String): ByteArray =
    bytes.copyOf().also { magic.toByteArray(StandardCharsets.US_ASCII).copyInto(it) }

/** A format 1 package (`LRP1`: magic, length, payload, signature), as the app stored before key IDs existed. */
private fun formatOnePackage(payload: String): ByteArray {
    val body = payload.toByteArray(StandardCharsets.UTF_8)
    return "LRP1".toByteArray(StandardCharsets.US_ASCII) + intBytes(body.size) + body + ByteArray(64)
}

private fun intBytes(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
)

class RegistryUpdateTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val keys = TestPackages.TRUSTED

    private fun registryDir(): File = folder.root.resolve("registry")

    @Test
    fun validPackageInstallsAndItsNetworkAndTokenBecomeVisible() {
        val baked = bakedRegistry()
        val dir = registryDir()

        val installed = RegistryUpdate.install(TestPackages.signed(TestPackages.payload()), keys, baked, dir)
            .getOrThrow()

        assertEquals(1L, installed.sequence)
        assertTrue(File(dir, RegistryUpdate.INSTALLED_FILE).isFile)
        val merged = RegistryUpdate.withInstalled(baked, dir, keys)
        assertEquals("Linea", assertNotNull(merged.network(4242L)).name)
        assertEquals("USDC", assertNotNull(merged.token(4242L, USDC_LINEA)).symbol)
        assertEquals(baked.networks().size + 1, merged.networks().size)
        assertEquals(baked.tokens().size + 1, merged.tokens().size)
        assertNull(baked.network(4242L))
    }

    @Test
    fun tamperedPayloadByteIsRejectedAndNothingIsStored() {
        val payload = TestPackages.payload()
        val bytes = TestPackages.signed(payload)
        bytes[16 + payload.indexOf("Linea")] = 'X'.code.toByte()
        val dir = registryDir()

        val result = RegistryUpdate.install(bytes, keys, bakedRegistry(), dir)

        assertIs<RegistryUpdateException.BadSignature>(failure(result))
        assertFalse(File(dir, RegistryUpdate.INSTALLED_FILE).exists())
    }

    @Test
    fun tamperedSignatureIsRejected() {
        val bytes = TestPackages.signed(TestPackages.payload())
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()

        assertIs<RegistryUpdateException.BadSignature>(failure(verifyBytes(bytes)))
    }

    @Test
    fun packageSignedWithAnUnknownKeyIsRejectedAndNamesTheKey() {
        val otherSeed = ByteArray(32) { 9 }
        val bytes = TestPackages.signed(TestPackages.payload(), seed = otherSeed)

        val refused = assertIs<RegistryUpdateException.UnknownKey>(failure(verifyBytes(bytes)))
        assertEquals(TrustedKey(TestPackages.publicKeyOf(otherSeed)).id, refused.keyId)
    }

    @Test
    fun packageIsRejectedWhenThereAreNoTrustedKeys() {
        val bytes = TestPackages.signed(TestPackages.payload())

        assertIs<RegistryUpdateException.UnknownKey>(failure(verifyBytes(bytes, keys = emptyList())))
    }

    @Test
    fun packageSignedWithARevokedKeyIsRefusedByName() {
        val bytes = TestPackages.signed(TestPackages.payload())
        val revoked = listOf(TrustedKey(TestPackages.PUBLIC_BYTES, KeyStatus.REVOKED))

        val refused = assertIs<RegistryUpdateException.RevokedKey>(failure(verifyBytes(bytes, keys = revoked)))
        assertEquals(TestPackages.TRUSTED.single().id, refused.keyId)
    }

    @Test
    fun rotationStopsTheOldKeyButTheInstalledSequenceStillBlocksRollback() {
        val baked = bakedRegistry()
        val dir = registryDir()
        val newSeed = ByteArray(32) { 5 }
        val rotated = listOf(
            TrustedKey(TestPackages.PUBLIC_BYTES, KeyStatus.REVOKED),
            TrustedKey(TestPackages.publicKeyOf(newSeed)),
        )
        RegistryUpdate.install(TestPackages.signed(TestPackages.payload(sequence = 2)), keys, baked, dir).getOrThrow()

        // The installed package is signed by the revoked key, so it no longer counts for the catalog...
        assertNull(RegistryUpdate.installedPackage(baked, dir, rotated))
        assertEquals(baked.networks(), RegistryUpdate.withInstalled(baked, dir, rotated).networks())
        // ...but its sequence still does: an older package from the new key cannot be installed.
        val older = assertIs<RegistryUpdateException.NotNewer>(
            failure(RegistryUpdate.install(TestPackages.signed(TestPackages.payload(sequence = 1), newSeed), rotated, baked, dir)),
        )
        assertEquals(2L, older.installed)
        assertTrue(RegistryUpdate.install(TestPackages.signed(TestPackages.payload(sequence = 3), newSeed), rotated, baked, dir).isSuccess)
    }

    @Test
    fun sequenceMustIncreaseSoAnOlderPackageCannotRollBack() {
        val baked = bakedRegistry()
        val dir = registryDir()
        fun offer(sequence: Long) = RegistryUpdate.install(
            TestPackages.signed(TestPackages.payload(sequence = sequence)),
            keys,
            baked,
            dir,
        )

        assertTrue(offer(2).isSuccess)
        val equal = assertIs<RegistryUpdateException.NotNewer>(failure(offer(2)))
        assertEquals(2L, equal.installed)
        assertEquals(2L, equal.offered)
        val older = assertIs<RegistryUpdateException.NotNewer>(failure(offer(1)))
        assertEquals(1L, older.offered)
        val stored = RegistryUpdate.verify(File(dir, RegistryUpdate.INSTALLED_FILE).readBytes(), keys, baked)
        assertEquals(2L, stored.getOrThrow().sequence)
        assertTrue(offer(3).isSuccess)
    }

    @Test
    fun anUnreadableInstalledPackageStillRefusesAnEqualOrOlderSequence() {
        val baked = bakedRegistry()
        val dir = registryDir()
        RegistryUpdate.install(TestPackages.signed(TestPackages.payload(sequence = 5)), keys, baked, dir).getOrThrow()
        File(dir, RegistryUpdate.INSTALLED_FILE).writeBytes(byteArrayOf(1, 2, 3))

        val equal = assertIs<RegistryUpdateException.NotNewer>(
            failure(RegistryUpdate.install(TestPackages.signed(TestPackages.payload(sequence = 5)), keys, baked, dir)),
        )
        assertEquals(5L, equal.installed)
        assertIs<RegistryUpdateException.NotNewer>(
            failure(RegistryUpdate.install(TestPackages.signed(TestPackages.payload(sequence = 4)), keys, baked, dir)),
        )
        assertTrue(RegistryUpdate.install(TestPackages.signed(TestPackages.payload(sequence = 6)), keys, baked, dir).isSuccess)
    }

    @Test
    fun anUnreadableInstalledPackageWithNoRecordNeverFallsBackToSequenceZero() {
        val baked = bakedRegistry()
        val dir = registryDir()
        RegistryUpdate.install(TestPackages.signed(TestPackages.payload(sequence = 5)), keys, baked, dir).getOrThrow()
        File(dir, RegistryUpdate.INSTALLED_FILE).writeBytes(byteArrayOf(1, 2, 3))
        File(dir, RegistryUpdate.SEQUENCE_FILE).delete()

        assertIs<RegistryUpdateException.SequenceUnknown>(
            failure(RegistryUpdate.install(TestPackages.signed(TestPackages.payload(sequence = 1)), keys, baked, dir)),
        )
        assertFalse(File(dir, RegistryUpdate.SEQUENCE_FILE).exists())
    }

    @Test
    fun anInstalledFormatOnePackageStillSetsTheSequenceAfterAnUpgrade() {
        val baked = bakedRegistry()
        val dir = registryDir().apply { mkdirs() }
        File(dir, RegistryUpdate.INSTALLED_FILE).writeBytes(formatOnePackage(TestPackages.payload(sequence = 5)))

        val refused = assertIs<RegistryUpdateException.NotNewer>(
            failure(RegistryUpdate.install(TestPackages.signed(TestPackages.payload(sequence = 4)), keys, baked, dir)),
        )
        assertEquals(5L, refused.installed)
    }

    @Test
    fun networkWithABakedChainIdIsRejected() {
        val imposter = """{"chainId":1,"explorerName":null,"feeModel":"L1","name":"Imposter","nativeDecimals":18,"nativeSymbol":"ETH","testnet":false}"""

        assertIs<RegistryUpdateException.Conflict>(failure(verifyPayload(TestPackages.payload(networks = imposter, tokens = ""))))
    }

    @Test
    fun tokenAlreadyInTheBakedCatalogIsRejected() {
        val usdc = """{"chainId":1,"contract":"$USDC_ETHEREUM","decimals":6,"name":"USD Coin","symbol":"USDC"}"""

        assertIs<RegistryUpdateException.Conflict>(failure(verifyPayload(TestPackages.payload(networks = "", tokens = usdc))))
    }

    @Test
    fun entriesRepeatedWithinOnePackageAreRejected() {
        val twoNetworks = "${TestPackages.LINEA_NETWORK},${TestPackages.LINEA_NETWORK}"
        val twoTokens = "${TestPackages.LINEA_USDC},${TestPackages.LINEA_USDC}"

        assertIs<RegistryUpdateException.Conflict>(failure(verifyPayload(TestPackages.payload(networks = twoNetworks, tokens = ""))))
        assertIs<RegistryUpdateException.Conflict>(failure(verifyPayload(TestPackages.payload(tokens = twoTokens))))
    }

    @Test
    fun tokenOnAChainThatIsNeitherBakedNorInThePackageIsRejected() {
        val token = """{"chainId":777,"contract":"0x0000000000000000000000000000000000000001","decimals":18,"name":"X","symbol":"X"}"""

        assertIs<RegistryUpdateException.InvalidEntry>(failure(verifyPayload(TestPackages.payload(networks = "", tokens = token))))
    }

    @Test
    fun installedTokenOnABakedChainIsAccepted() {
        val token = """{"chainId":8453,"contract":"0x0000000000000000000000000000000000000002","decimals":18,"name":"Test Token","symbol":"TST"}"""

        val pkg = verifyPayload(TestPackages.payload(networks = "", tokens = token)).getOrThrow()

        assertEquals(1, pkg.tokens.size)
    }

    @Test
    fun otherFormatVersionIsRejectedWithItsVersion() {
        val refused = assertIs<RegistryUpdateException.UnsupportedFormat>(
            failure(verifyPayload(TestPackages.payload(formatVersion = 3))),
        )
        assertEquals(3L, refused.version)
        assertEquals(2L, refused.supported)
    }

    @Test
    fun otherMagicIsRefusedAsUnsupportedBeforeAnythingElseIsChecked() {
        val signed = TestPackages.signed(TestPackages.payload())

        assertEquals(1L, assertIs<RegistryUpdateException.UnsupportedFormat>(failure(verifyBytes(withMagic(signed, "LRP1")))).version)
        assertEquals(3L, assertIs<RegistryUpdateException.UnsupportedFormat>(failure(verifyBytes(withMagic(signed, "LRP3")))).version)
    }

    @Test
    fun aPackageThatNeedsANewerSchemaIsIncompatible() {
        val refused = assertIs<RegistryUpdateException.Incompatible>(
            failure(verifyPayload(TestPackages.payload(minSchemaVersion = 2))),
        )
        assertEquals(2L, refused.required)
        assertEquals(1L, refused.supported)
    }

    @Test
    fun outOfRangeOrMalformedEntryValuesAreRejected() {
        val network = TestPackages.LINEA_NETWORK
        val token = TestPackages.LINEA_USDC
        val cases = listOf(
            "token decimals above 36" to TestPackages.payload(tokens = token.replace("\"decimals\":6", "\"decimals\":37")),
            "uppercase token contract" to TestPackages.payload(
                tokens = token.replace("0x176211869ca2b568f2a7d4ee941e073a821ee1ff", "0x176211869CA2B568F2A7D4EE941E073A821EE1FF"),
            ),
            "short token contract" to TestPackages.payload(tokens = token.replace("ee1ff", "ee1")),
            "chainId zero" to TestPackages.payload(networks = network.replace("\"chainId\":4242", "\"chainId\":0")),
            "nativeDecimals above 36" to TestPackages.payload(
                networks = network.replace("\"nativeDecimals\":18", "\"nativeDecimals\":37"),
            ),
            "blank network name" to TestPackages.payload(networks = network.replace("\"name\":\"Linea\"", "\"name\":\"  \"")),
            "control character in name" to TestPackages.payload(
                networks = network.replace("\"name\":\"Linea\"", "\"name\":\"Li\\u0007nea\""),
            ),
            "bidi override in token name" to TestPackages.payload(
                tokens = token.replace("\"name\":\"USDC\"", "\"name\":\"US\\u202EDC\""),
            ),
            "name longer than 64 characters" to TestPackages.payload(
                networks = network.replace("\"name\":\"Linea\"", "\"name\":\"${"x".repeat(65)}\""),
            ),
        )
        for ((label, payload) in cases) {
            assertIs<RegistryUpdateException.InvalidEntry>(verifyPayload(payload).exceptionOrNull(), label)
        }
    }

    @Test
    fun testnetNetworkCannotBeAddedByAPackage() {
        val testnet = TestPackages.LINEA_NETWORK.replace("\"testnet\":false", "\"testnet\":true")

        val payload = TestPackages.payload(networks = testnet, tokens = "")

        assertIs<RegistryUpdateException.InvalidEntry>(verifyPayload(payload).exceptionOrNull())
    }

    @Test
    fun malformedPayloadsAreRejected() {
        val network = TestPackages.LINEA_NETWORK
        val cases = listOf(
            "sequence zero" to TestPackages.payload(sequence = 0),
            "impossible date" to TestPackages.payload(createdAt = "2026-02-30"),
            "unknown fee model" to TestPackages.payload(networks = network.replace("L2_ROLLUP", "L3")),
            "unknown field" to TestPackages.payload(networks = network.replace("\"testnet\":false", "\"testnet\":false,\"extra\":1")),
            "missing explorerName" to TestPackages.payload(networks = network.replace("\"explorerName\":\"Lineascan\",", "")),
            "not a JSON object" to "[1,2,3]",
            "formatVersion as a string" to TestPackages.payload().replace("\"formatVersion\":2", "\"formatVersion\":\"2\""),
            "no minSchemaVersion" to TestPackages.payload().replace("\"minSchemaVersion\":1,", ""),
        )
        for ((label, payload) in cases) {
            assertIs<RegistryUpdateException.Malformed>(verifyPayload(payload).exceptionOrNull(), label)
        }
    }

    @Test
    fun wrongMagicLengthAndTruncationAreRejected() {
        val good = TestPackages.signed(TestPackages.payload())
        val badMagic = good.copyOf().also { it[0] = 'X'.code.toByte() }
        val badLength = good.copyOf().also { it[7] = (it[7] + 1).toByte() }

        assertIs<RegistryUpdateException.Malformed>(failure(verifyBytes(badMagic)))
        assertIs<RegistryUpdateException.Malformed>(failure(verifyBytes(badLength)))
        assertIs<RegistryUpdateException.Malformed>(failure(verifyBytes(good.copyOf(40))))
    }

    @Test
    fun packageOverOneMebibyteIsRejectedBeforeAnythingIsParsed() {
        assertIs<RegistryUpdateException.Malformed>(failure(verifyBytes(ByteArray(PackageFormat.MAX_BYTES + 1))))
    }

    @Test
    fun payloadThatIsNotUtf8IsRejected() {
        val payload = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "{}".toByteArray()

        assertIs<RegistryUpdateException.Malformed>(failure(verifyBytes(TestPackages.signedBytes(payload))))
    }

    @Test
    fun committedSampleVerifiesAgainstTheDebugKey() {
        // The committed sample adds Linea (59144), which the app now ships. Verify it against a catalog without
        // that chain, so the check still covers the signature and the package format.
        val bakedWithoutSampleChain = bakedRegistry().let { baked ->
            Registry(
                baked.networks().filter { it.chainId != 59144L },
                baked.tokens().filter { it.chainId != 59144L },
            )
        }
        val debugKeys = TrustedKeys.load(fileReader(MAIN, DEBUG))
        val pkg = RegistryUpdate.verify(File(SAMPLE).readBytes(), debugKeys, bakedWithoutSampleChain).getOrThrow()

        assertEquals(listOf(59144L), pkg.networks.map { it.chainId })
        assertEquals("Linea", pkg.networks.single().name)
        assertEquals(USDC_LINEA, pkg.tokens.single().contract)
        assertEquals(6, pkg.tokens.single().decimals)
    }

    @Test
    fun committedSampleIsRefusedByTheReleaseKeysAlone() {
        // The development key is listed only in the debug source set, so a release build (main assets only) refuses it.
        val releaseKeys = TrustedKeys.load(fileReader(MAIN))

        assertIs<RegistryUpdateException.UnknownKey>(
            failure(RegistryUpdate.verify(File(SAMPLE).readBytes(), releaseKeys, bakedRegistry())),
        )
    }

    @Test
    fun corruptInstalledFileWithNoRecordIsIgnoredAndRefusesInstalls() {
        val dir = registryDir().apply { mkdirs() }
        File(dir, RegistryUpdate.INSTALLED_FILE).writeBytes(byteArrayOf(1, 2, 3))
        val baked = bakedRegistry()

        assertEquals(baked.networks(), RegistryUpdate.withInstalled(baked, dir, keys).networks())
        assertIs<RegistryUpdateException.SequenceUnknown>(
            failure(RegistryUpdate.install(TestPackages.signed(TestPackages.payload()), keys, baked, dir)),
        )
    }

    @Test
    fun installedPackageThatNoLongerFitsTheBakedCatalogIsIgnored() {
        val dir = registryDir()
        RegistryUpdate.install(TestPackages.signed(TestPackages.payload()), keys, bakedRegistry(), dir).getOrThrow()
        // An app update that bakes chain 4242 after the package was installed.
        val bakedWithLinea = RegistryLoader.load(
            fakeReader(
                RegistryLoader.NETWORKS_PATH to
                    """{"networks":[{"chainId":4242,"name":"Linea","nativeSymbol":"ETH","feeModel":"L2_ROLLUP","explorerName":"Lineascan","testnet":false}]}""",
                RegistryLoader.TOKENS_PATH to """{"tokens":[]}""",
            ),
        )

        val merged = RegistryUpdate.withInstalled(bakedWithLinea, dir, keys)

        assertEquals(bakedWithLinea.networks(), merged.networks())
        assertEquals(bakedWithLinea.tokens(), merged.tokens())
    }

    @Test
    fun withAdditionsRefusesToReplaceABakedChain() {
        val replacement = NetworkEntry(1L, "Imposter", "ETH", 18, FeeModel.L1, null, false)

        assertFailsWith<IllegalArgumentException> { bakedRegistry().withAdditions(listOf(replacement), emptyList()) }
    }

    @Test
    fun readPackageReturnsAFileExactlyAtTheCap() {
        val bytes = ByteArray(PackageFormat.MAX_BYTES) { 7 }

        assertContentEquals(bytes, RegistryUpdate.readPackage(bytes.inputStream()))
    }

    @Test
    fun readPackageRefusesAFileOneByteOverTheCap() {
        val bytes = ByteArray(PackageFormat.MAX_BYTES + 1) { 7 }

        assertIs<RegistryUpdateException.Malformed>(failure(runCatching { RegistryUpdate.readPackage(bytes.inputStream()) }))
    }

    @Test
    fun readPackageHoldsTheCapAcrossShortReads() {
        val atCap = ByteArray(PackageFormat.MAX_BYTES) { it.toByte() }
        val overCap = ByteArray(PackageFormat.MAX_BYTES + 1) { it.toByte() }

        assertContentEquals(atCap, RegistryUpdate.readPackage(TrickleInputStream(atCap, chunk = 3)))
        assertIs<RegistryUpdateException.Malformed>(
            failure(runCatching { RegistryUpdate.readPackage(TrickleInputStream(overCap, chunk = 3)) }),
        )
    }
}

/** Hands out at most [chunk] bytes per read call, the way a slow or network-backed stream may. */
private class TrickleInputStream(private val bytes: ByteArray, private val chunk: Int) : InputStream() {
    private var position = 0

    override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xff else -1

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(length, chunk, bytes.size - position)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }
}
