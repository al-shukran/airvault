package app.lensly.tools.registrypack

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val passphrase = "correct horse battery staple".toCharArray()

    /** Magic, length and key ID: the bytes before the payload. */
    private val headerBytes = 16

    /** Runs the tool with [pass] as its passphrase and returns the exit code. Its output is discarded. */
    private fun tool(vararg args: String, pass: CharArray = passphrase): Int {
        val sink = PrintStream(ByteArrayOutputStream())
        return runTool(args.toList(), sink, sink) { pass }
    }

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
    fun packageHeaderNamesTheFormatAndTheIdOfItsSigningKey() {
        val bytes = Pack.build(sourceJson, seed).first

        assertEquals("LRP2", String(bytes, 0, 4, StandardCharsets.US_ASCII))
        assertContentEquals(Pack.keyIdOf(publicKey), bytes.copyOfRange(8, headerBytes))
    }

    @Test
    fun tamperedPayloadByteFailsVerification() {
        val bytes = Pack.build(sourceJson, seed).first
        bytes[headerBytes + 20] = (bytes[headerBytes + 20] + 1).toByte()

        assertFails { Pack.verify(bytes, publicKey) }
    }

    @Test
    fun tamperedKeyIdByteFailsVerification() {
        val bytes = Pack.build(sourceJson, seed).first
        bytes[8] = (bytes[8] + 1).toByte()

        assertFails { Pack.verify(bytes, publicKey) }
    }

    @Test
    fun packageFailsVerificationWithAnotherKey() {
        val bytes = Pack.build(sourceJson, seed).first

        assertFails { Pack.verify(bytes, Pack.publicKeyOf(otherSeed)) }
    }

    @Test
    fun committedSampleIsTheSourceSignedWithTheDebugKey() {
        val debugKey = json.parseToJsonElement(File("../../shared/src/debug/assets/registry/trusted-keys.debug.json").readText())
            .jsonObject.getValue("keys").jsonArray.single().jsonObject.getValue("publicKey").jsonPrimitive.content.hexToBytes()
        val committed = File("samples/example.lrp").readBytes()

        assertEquals(source, Pack.verify(committed, debugKey))
        assertContentEquals(Pack.canonicalPayload(source), committed.copyOfRange(headerBytes, committed.size - 64))
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
            "formatVersion one" to source.copy(formatVersion = 1),
            "minSchemaVersion above this tool" to source.copy(minSchemaVersion = 3),
            "minSchemaVersion zero" to source.copy(minSchemaVersion = 0),
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
    fun keygenWritesAnEncryptedKeyThatOpensWithItsPassphrase() {
        val dir = folder.root.toPath().resolve("keys")

        val made = generateKeys(dir, passphrase)

        assertEquals(made.toHex(), Files.readString(dir.resolve("release.pub")).trim())
        val stored = Files.readString(dir.resolve("release.key"))
        val privateSeed = decryptSeed(stored, passphrase)
        assertContentEquals(made, Pack.publicKeyOf(privateSeed))
        assertFalse(stored.contains(privateSeed.toHex()), "the seed must not be stored in the clear")
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(dir.resolve("release.key")),
        )
        assertFails { generateKeys(dir, passphrase) }
        assertTrue(Files.exists(dir.resolve("release.key")))
    }

    @Test
    fun aWrongPassphraseOrAnAlteredKeyFileIsRefused() {
        val dir = folder.root.toPath().resolve("keys")
        generateKeys(dir, passphrase)
        val stored = Files.readString(dir.resolve("release.key")).trim()
        val altered = stored.dropLast(1) + if (stored.last() == '0') '1' else '0'

        assertFailsWith<IllegalArgumentException> { decryptSeed(stored, "a different passphrase".toCharArray()) }
        assertFailsWith<IllegalArgumentException> { decryptSeed(altered, passphrase) }
    }

    @Test
    fun passphraseMustBeAtLeastTwelveCharacters() {
        assertFailsWith<IllegalArgumentException> {
            generateKeys(folder.root.toPath().resolve("short"), "too short".toCharArray())
        }
    }

    @Test
    fun keygenBuildAndVerifyRoundTripWithThePassphrase() {
        val dir = folder.root.toPath().resolve("keys")
        val out = folder.root.toPath().resolve("round-trip.lrp").toString()
        val key = dir.resolve("release.key").toString()
        val pub = dir.resolve("release.pub").toString()

        assertEquals(0, tool("keygen", "--out", dir.toString()))
        assertEquals(0, tool("build", "--in", "samples/example-entries.json", "--key", key, "--out", out))
        assertEquals(0, tool("verify", "--pub", pub, out))
        val wrong = "wrong passphrase!".toCharArray()
        assertEquals(1, tool("build", "--in", "samples/example-entries.json", "--key", key, "--out", out, pass = wrong))
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
            "dynamic param type" to whitelistSource.copy(
                whitelist = listOf(entry.copy(params = entry.params.dropLast(1) + ParamDto("to", "address[]"))),
            ),
            "param type of another type" to whitelistSource.copy(
                whitelist = listOf(entry.copy(params = entry.params.mapIndexed { index, param ->
                    if (index == 0) param.copy(type = "address") else param
                })),
            ),
            "upgradeable without a proxy" to whitelistSource.copy(
                whitelist = listOf(entry.copy(upgradeable = true, proxy = null)),
            ),
            "proxy on an entry that is not upgradeable" to whitelistSource.copy(
                whitelist = listOf(proxy.copy(upgradeable = false)),
            ),
        )
        for ((label, content) in cases) {
            assertFailsWith<IllegalArgumentException>(label) {
                Pack.build(json.encodeToString(PackageDto.serializer(), content), seed)
            }
        }
    }

    @Test
    fun denylistAndWalletsRoundTripThroughTheSignedPackage() {
        val (bytes, content) = Pack.build(sourceJson, seed)

        assertEquals(source, content)
        assertEquals(source, Pack.verify(bytes, publicKey))
        assertEquals(2, source.denylist?.size)
        assertEquals(1, source.wallets?.size)
    }

    @Test
    fun aPackageWithoutDenylistOrWalletsKeepsItsOldPayloadBytes() {
        val payload = String(
            Pack.canonicalPayload(source.copy(minSchemaVersion = 1, denylist = null, wallets = null)),
            StandardCharsets.UTF_8,
        )

        assertFalse(payload.contains("denylist"))
        assertFalse(payload.contains("wallets"))
    }

    @Test
    fun anEmptyDenylistIsWrittenSoItClearsTheBuiltInList() {
        val payload = String(Pack.canonicalPayload(source.copy(denylist = emptyList())), StandardCharsets.UTF_8)

        assertTrue(payload.contains("\"denylist\":[]"))
    }

    @Test
    fun tamperedDenylistByteFailsVerification() {
        val bytes = Pack.build(sourceJson, seed).first
        val at = String(bytes, StandardCharsets.ISO_8859_1).indexOf("Sample entry: an unlimited")
        bytes[at] = (bytes[at] + 1).toByte()

        assertFails { Pack.verify(bytes, publicKey) }
    }

    @Test
    fun invalidDenylistAndWalletEntriesAreRefused() {
        val denylist = source.denylist!!
        val wallet = source.wallets!!.single()
        val cases = mapOf(
            "denylist without minSchemaVersion 2" to source.copy(minSchemaVersion = 1),
            "selector of the wrong length" to source.copy(denylist = listOf(denylist[0].copy(selector = "0x095ea7b"))),
            "contract without a chainId" to source.copy(denylist = listOf(denylist[1].copy(chainId = null))),
            "chainId zero" to source.copy(denylist = listOf(denylist[1].copy(chainId = 0))),
            "entry naming no selector, contract or type" to source.copy(
                denylist = listOf(DenylistDto(severity = DenylistSeverity.WARN, reason = "Reason.")),
            ),
            "condition without a selector" to source.copy(
                denylist = listOf(denylist[1].copy(condition = DenylistCondition.UNLIMITED)),
            ),
            "blank reason" to source.copy(denylist = listOf(denylist[0].copy(reason = " "))),
            "bidi control in reason" to source.copy(denylist = listOf(denylist[0].copy(reason = "Approve‮ tokens"))),
            "reason over 240 characters" to source.copy(denylist = listOf(denylist[0].copy(reason = "x".repeat(241)))),
            "wallet without a note" to source.copy(wallets = listOf(wallet.copy(note = " "))),
            "wallet without a code format" to source.copy(wallets = listOf(wallet.copy(formats = emptyList()))),
            "wallet without limitations" to source.copy(wallets = listOf(wallet.copy(limitations = ""))),
            "wallet named twice" to source.copy(wallets = listOf(wallet, wallet.copy(note = "Again"))),
        )
        for ((label, content) in cases) {
            assertFailsWith<IllegalArgumentException>(label) {
                Pack.build(json.encodeToString(PackageDto.serializer(), content), seed)
            }
        }
    }
}
