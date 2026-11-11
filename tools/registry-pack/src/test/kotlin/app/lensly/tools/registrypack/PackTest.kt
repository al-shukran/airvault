package app.lensly.tools.registrypack

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Paths are relative to :tools:registry-pack, the working directory for its unit tests. */
class PackTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val json = Json
    private val seed = ByteArray(32) { (it * 5 + 1).toByte() }
    private val otherSeed = ByteArray(32) { (it * 11 + 2).toByte() }
    private val publicKey = Pack.publicKeyOf(seed)
    private val sourceJson = File("samples/example-entries.json").readText()
    private val source = json.decodeFromString<PackageDto>(sourceJson)

    @Test
    fun buildingTwiceFromTheSameSourceGivesIdenticalBytes() {
        assertContentEquals(Pack.build(sourceJson, seed).first, Pack.build(sourceJson, seed).first)
    }

    @Test
    fun entryOrderInTheSourceDoesNotChangeTheBytes() {
        val extra = source.tokens.single().copy(contract = "0x1111111111111111111111111111111111111111", symbol = "TST")
        val ordered = source.copy(tokens = listOf(source.tokens.single(), extra))
        val reversed = ordered.copy(tokens = ordered.tokens.reversed())

        assertContentEquals(
            Pack.build(json.encodeToString(PackageDto.serializer(), ordered), seed).first,
            Pack.build(json.encodeToString(PackageDto.serializer(), reversed), seed).first,
        )
    }

    @Test
    fun builtPackageVerifiesAndReadsBackTheSameContent() {
        val (bytes, content) = Pack.build(sourceJson, seed)

        assertEquals(source, content)
        assertEquals(content, Pack.verify(bytes, publicKey))
    }

    @Test
    fun tamperedPayloadByteFailsVerification() {
        val bytes = Pack.build(sourceJson, seed).first
        val payloadStart = 8
        bytes[payloadStart + 20] = (bytes[payloadStart + 20] + 1).toByte()

        assertFails { Pack.verify(bytes, publicKey) }
    }

    @Test
    fun packageFailsVerificationWithAnotherKey() {
        val bytes = Pack.build(sourceJson, seed).first

        assertFails { Pack.verify(bytes, Pack.publicKeyOf(otherSeed)) }
    }

    @Test
    fun committedSampleIsTheSourceSignedWithTheBakedKey() {
        val bakedKey = File("../../app/src/main/assets/registry/release.pub").readText().hexToBytes()
        val committed = File("samples/example.lrp").readBytes()

        assertEquals(source, Pack.verify(committed, bakedKey))
        assertContentEquals(Pack.canonicalPayload(source), committed.copyOfRange(8, committed.size - 64))
    }

    @Test
    fun invalidEntriesAreRefused() {
        val network = source.networks.single()
        val token = source.tokens.single()
        val cases = mapOf(
            "token decimals above 36" to source.copy(tokens = listOf(token.copy(decimals = 37))),
            "uppercase token contract" to source.copy(
                tokens = listOf(token.copy(contract = token.contract.uppercase().replaceFirst("0X", "0x"))),
            ),
            "short token contract" to source.copy(tokens = listOf(token.copy(contract = "0x1234"))),
            "chainId zero" to source.copy(networks = listOf(network.copy(chainId = 0))),
            "testnet network" to source.copy(networks = listOf(network.copy(testnet = true))),
            "nativeDecimals above 36" to source.copy(networks = listOf(network.copy(nativeDecimals = 37))),
            "sequence zero" to source.copy(sequence = 0),
            "formatVersion two" to source.copy(formatVersion = 2),
            "impossible date" to source.copy(createdAt = "2026-02-30"),
            "control character in name" to source.copy(networks = listOf(network.copy(name = "Li\u0007nea"))),
            "bidi control in token name" to source.copy(tokens = listOf(token.copy(name = "USD‮Coin"))),
            "repeated network" to source.copy(networks = listOf(network, network.copy(name = "Again"))),
            "repeated token" to source.copy(tokens = listOf(token, token.copy(symbol = "USDC2"))),
        )
        for ((label, content) in cases) {
            assertFailsWith<IllegalArgumentException>(label) {
                Pack.build(json.encodeToString(PackageDto.serializer(), content), seed)
            }
        }
    }

    @Test
    fun unknownFieldsAreRefused() {
        val withExtra = sourceJson.replaceFirst("\"sequence\"", "\"extra\": true, \"sequence\"")

        assertFailsWith<IllegalArgumentException> { Pack.build(withExtra, seed) }
    }

    @Test
    fun keygenWritesMatchingKeysAndRefusesToOverwrite() {
        val dir = folder.root.toPath().resolve("keys")

        val publicKey = generateKeys(dir)

        assertEquals(publicKey.toHex(), Files.readString(dir.resolve("release.pub")).trim())
        assertContentEquals(publicKey, Pack.publicKeyOf(readKey(dir.resolve("release.key"))))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(dir.resolve("release.key")),
        )
        assertFails { generateKeys(dir) }
        assertTrue(Files.exists(dir.resolve("release.key")))
    }

    private val whitelistJson = File("samples/example-whitelist-entries.json").readText()
    private val whitelistSource = json.decodeFromString<PackageDto>(whitelistJson)

    @Test
    fun whitelistRoundTripsThroughTheSignedPackage() {
        val (bytes, content) = Pack.build(whitelistJson, seed)

        assertEquals(whitelistSource, content)
        assertEquals(whitelistSource, Pack.verify(bytes, publicKey))
        assertEquals(2, whitelistSource.whitelist.size)
    }

    @Test
    fun aPackageWithoutAWhitelistKeepsItsOldPayloadBytes() {
        val payload = String(Pack.canonicalPayload(source), StandardCharsets.UTF_8)

        assertFalse(payload.contains("whitelist"))
    }

    @Test
    fun tamperedWhitelistByteFailsVerification() {
        val bytes = Pack.build(whitelistJson, seed).first
        val at = String(bytes, StandardCharsets.ISO_8859_1).indexOf("swapExactTokensForTokens")
        bytes[at] = (bytes[at] + 1).toByte()

        assertFails { Pack.verify(bytes, publicKey) }
    }

    @Test
    fun invalidWhitelistEntriesAreRefused() {
        val entry = whitelistSource.whitelist.first()
        val proxy = whitelistSource.whitelist.last()
        val cases = mapOf(
            "selector of another signature" to whitelistSource.copy(whitelist = listOf(entry.copy(selector = "0x095ea7b3"))),
            "uppercase whitelist contract" to whitelistSource.copy(
                whitelist = listOf(entry.copy(contract = "0x" + "AB".repeat(20))),
            ),
            "short selector" to whitelistSource.copy(whitelist = listOf(entry.copy(selector = "0x38ed"))),
            "signature without parameter list" to whitelistSource.copy(whitelist = listOf(entry.copy(signature = "swap"))),
            "blank meaning" to whitelistSource.copy(whitelist = listOf(entry.copy(meaning = " "))),
            "meaning over 240 characters" to whitelistSource.copy(whitelist = listOf(entry.copy(meaning = "x".repeat(241)))),
            "bidi control in meaning" to whitelistSource.copy(whitelist = listOf(entry.copy(meaning = "Swap‮ tokens"))),
            "proxy naming nothing" to whitelistSource.copy(whitelist = listOf(proxy.copy(proxy = ProxyDto()))),
            "proxy code hash too short" to whitelistSource.copy(
                whitelist = listOf(proxy.copy(proxy = ProxyDto(implementationCodeHash = "0x1234"))),
            ),
            "whitelist chainId zero" to whitelistSource.copy(whitelist = listOf(entry.copy(chainId = 0))),
            "repeated whitelist entry" to whitelistSource.copy(whitelist = listOf(entry, entry.copy(meaning = "Again"))),
        )
        for ((label, content) in cases) {
            assertFailsWith<IllegalArgumentException>(label) {
                Pack.build(json.encodeToString(PackageDto.serializer(), content), seed)
            }
        }
    }
}
