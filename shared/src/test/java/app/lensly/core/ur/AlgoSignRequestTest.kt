package app.lensly.core.ur

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.AlgoSignRequestEncoder
import app.airvault.chains.algorand.AlgoSignType
import app.airvault.core.signer.UNKNOWN_REQUEST
import app.lensly.app.export.partsOf
import com.sparrowwallet.hummingbird.UR
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class AlgoSignRequestTest {

    private val requestId = ByteArray(16) { (0x20 + it).toByte() }
    private val signData = ByteArray(96) { (it * 3).toByte() }
    private val address = "A".repeat(58)

    private fun scanned(parts: List<String>): UR {
        val scanner = UrScanner()
        parts.forEach { scanner.receive(it) }
        return assertNotNull(scanner.result())
    }

    private fun ledgerPath(account: Int = 0) =
        listOf(PathStep(44, true), PathStep(283, true), PathStep(account, true), PathStep(0, false), PathStep(0, false))

    private fun requestOf(
        signData: ByteArray = this.signData,
        path: List<PathStep> = ledgerPath(),
        fingerprint: Int? = 0x73c5da0a,
        signType: AlgoSignType = AlgoSignType.TRANSACTION,
    ) = AlgoSignRequest(requestId, signData, path, fingerprint, address, "Lumio", signType)

    @Test
    fun aRequestReadsBackToItsFields() {
        val request = requestOf()

        val parsed = okOf(AlgoSignRequest.decode(scanned(partsOf(AlgoSignRequestEncoder.encode(request)))))

        assertContentEquals(requestId, parsed.requestId)
        assertContentEquals(signData, parsed.signData)
        assertEquals(request.derivationPath, parsed.derivationPath)
        assertEquals(0x73c5da0a, parsed.sourceFingerprint)
        assertEquals(address, parsed.address)
        assertEquals("Lumio", parsed.origin)
        assertEquals(AlgoSignType.TRANSACTION, parsed.signType)
    }

    @Test
    fun aMessageWithoutFingerprintReadsBack() {
        val request = requestOf(fingerprint = null, signType = AlgoSignType.MESSAGE)

        val parsed = okOf(AlgoSignRequest.decode(scanned(partsOf(AlgoSignRequestEncoder.encode(request)))))

        assertNull(parsed.sourceFingerprint)
        assertEquals(AlgoSignType.MESSAGE, parsed.signType)
    }

    @Test
    fun aLargeRequestIsAnAnimatedCodeThatReadsBack() {
        val large = ByteArray(900) { (it * 13).toByte() }

        val parts = partsOf(AlgoSignRequestEncoder.encode(requestOf(signData = large), maxFragment = 100))

        assertTrue(parts.size > 1)
        assertContentEquals(large, okOf(AlgoSignRequest.decode(scanned(parts))).signData)
    }

    @Test
    fun theFrameCarriesKeysOneToSixWithTheirTypes() {
        // The request is about 220 bytes, so it is an animated code. Scanning every part gives the whole payload.
        val parts = partsOf(AlgoSignRequestEncoder.encode(requestOf()))
        assertTrue(parts.first().startsWith("UR:ALGO-SIGN-REQUEST/"), parts.first())

        val root = Cbor.decode(scanned(parts).cborBytes) as Cbor.Dict
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), root.entries.map { (it.first as Cbor.Uint).value })
        assertEquals(37L, (root.entries[0].second as Cbor.Tagged).tag)
        assertIs<Cbor.Bytes>(root.entries[1].second)
        assertEquals(304L, (root.entries[2].second as Cbor.Tagged).tag)
        assertIs<Cbor.Text>(root.entries[3].second)
        assertIs<Cbor.Text>(root.entries[4].second)
        assertEquals(1L, (root.entries[5].second as Cbor.Uint).value)
    }

    @Test
    fun aRequestThatLenslyWouldRefuseIsNotEncoded() {
        assertFailsWith<IllegalArgumentException> { AlgoSignRequestEncoder.encode(requestOf(signData = ByteArray(0))) }
        assertFailsWith<IllegalArgumentException> { AlgoSignRequestEncoder.encode(requestOf(signData = ByteArray(64 * 1024 + 1))) }
        assertFailsWith<IllegalArgumentException> { AlgoSignRequestEncoder.encode(requestOf(path = ledgerPath().dropLast(1))) }
        assertFailsWith<IllegalArgumentException> {
            AlgoSignRequestEncoder.encode(AlgoSignRequest(ByteArray(15), signData, ledgerPath(), null, address, "Lumio", AlgoSignType.TRANSACTION))
        }
    }

    @Test
    fun signTypeOneAndTwoDecodeAndOtherValuesAreRefused() {
        assertEquals(AlgoSignType.TRANSACTION, okOf(AlgoSignRequest.decode(payload(6L to Cbor.Uint(1)))).signType)
        assertEquals(AlgoSignType.MESSAGE, okOf(AlgoSignRequest.decode(payload(6L to Cbor.Uint(2)))).signType)
        for (wire in listOf(0L, 3L, 255L)) {
            assertTrue(rejectedReason(payload(6L to Cbor.Uint(wire))).contains("unsupported signType"), "signType $wire")
        }
    }

    @Test
    fun everyKeyIsRequired() {
        val names = mapOf(
            1L to "requestId",
            2L to "signData",
            3L to "derivationPath",
            4L to "address",
            5L to "origin",
            6L to "signType",
        )
        for ((key, name) in names) {
            assertTrue(rejectedReason(payload(key to null)).contains("$name is missing"), name)
        }
    }

    @Test
    fun aKeyLenslyDoesNotReadIsRefusedNotIgnored() {
        assertEquals(
            "This request uses something this Lensly does not know. It may come from a newer Lumio. Nothing was signed.",
            rejectedReason(payload(7L to Cbor.Uint(0))),
        )
    }

    @Test
    fun aKeyOutsideTheSixFieldsIsRefusedIncludingOneFromTheFormerReservedRange() {
        assertEquals(UNKNOWN_REQUEST, rejectedReason(payload(999L to Cbor.Uint(0))))
        assertEquals(UNKNOWN_REQUEST, rejectedReason(payload(1000L to Cbor.Uint(0))))
    }

    @Test
    fun theDerivationPathIsTheLedgerAlgorandShape() {
        assertIs<AlgoSignRequest.Result.Ok>(AlgoSignRequest.decode(payload(3L to keypath(ledgerComponents(account = 7)))))

        val refusals = mapOf(
            "Solana's coin type" to components(44L to true, 501L to true, 0L to true, 0L to false, 0L to false),
            "Ethereum's coin type" to components(44L to true, 60L to true, 0L to true, 0L to false, 0L to false),
            "a purpose of 49" to components(49L to true, 283L to true, 0L to true, 0L to false, 0L to false),
            "a non-hardened account" to components(44L to true, 283L to true, 0L to false, 0L to false, 0L to false),
            "a hardened change step" to components(44L to true, 283L to true, 0L to true, 0L to true, 0L to false),
            "a change of 1" to components(44L to true, 283L to true, 0L to true, 1L to false, 0L to false),
            "a non-hardened index step" to components(44L to true, 283L to true, 0L to true, 0L to false, 1L to false),
            "a path of four steps" to components(44L to true, 283L to true, 0L to true, 0L to false),
            "a path of six steps" to components(44L to true, 283L to true, 0L to true, 0L to false, 0L to false, 0L to false),
        )
        for ((name, path) in refusals) {
            assertIs<AlgoSignRequest.Result.Rejected>(AlgoSignRequest.decode(payload(3L to keypath(path))), name)
        }

        // A tag 305 wrapper is a wrong tag, not a keypath.
        assertTrue(rejectedReason(payload(3L to Cbor.Tagged(305, dictOf(1L to Cbor.Arr(ledgerComponents()))))).contains("tag 304"))
    }

    @Test
    fun signDataIsNonEmptyAndAtMostSixtyFourKiB() {
        assertTrue(rejectedReason(payload(2L to Cbor.Bytes(ByteArray(0)))).contains("signData is empty"))

        val atLimit = payload(2L to Cbor.Bytes(ByteArray(AlgoSignRequest.MAX_SIGN_DATA_BYTES)))
        assertEquals(AlgoSignRequest.MAX_SIGN_DATA_BYTES, okOf(AlgoSignRequest.decode(atLimit)).signData.size)

        val overLimit = payload(2L to Cbor.Bytes(ByteArray(64 * 1024 + 1)))
        assertTrue(rejectedReason(overLimit).contains("signData exceeds"))
    }

    @Test
    fun aRequestIdIsSixteenBytesUnderTag37() {
        assertTrue(rejectedReason(payload(1L to Cbor.Tagged(37, Cbor.Bytes(ByteArray(15))))).contains("16 bytes"))
        assertTrue(rejectedReason(payload(1L to Cbor.Tagged(38, Cbor.Bytes(requestId)))).contains("tag 37"))
        assertTrue(rejectedReason(payload(1L to Cbor.Bytes(requestId))).contains("tag 37"))
    }

    @Test
    fun addressAndOriginMustBeText() {
        assertTrue(rejectedReason(payload(4L to Cbor.Bytes(ByteArray(58)))).contains("address must be text"))
        assertTrue(rejectedReason(payload(5L to Cbor.Bytes(byteArrayOf(1)))).contains("origin must be text"))
    }

    @Test
    fun aUrOfAnotherTypeIsRefused() {
        assertIs<AlgoSignRequest.Result.Ok>(AlgoSignRequest.decode(UR("algo-sign-request", payload())))
        assertIs<AlgoSignRequest.Result.Rejected>(AlgoSignRequest.decode(UR("algo-signature", payload())))
        assertIs<AlgoSignRequest.Result.Rejected>(AlgoSignRequest.decode(UR("sol-sign-request", payload())))
    }

    @Test
    fun anOriginOfTheCapDecodesAndOneByteMoreIsRefused() {
        assertEquals("a".repeat(512), okOf(AlgoSignRequest.decode(payload(5L to Cbor.Text("a".repeat(512))))).origin)
        assertEquals("origin exceeds 512 bytes", rejectedReason(payload(5L to Cbor.Text("a".repeat(513)))))
        // 256 two-byte characters are 512 bytes, which is at the cap. One more ASCII byte makes 513.
        val atCap = "\u00e9".repeat(256)
        assertEquals(atCap, okOf(AlgoSignRequest.decode(payload(5L to Cbor.Text(atCap)))).origin)
        assertEquals("origin exceeds 512 bytes", rejectedReason(payload(5L to Cbor.Text(atCap + "a"))))
    }

    @Test
    fun anOriginWithACharacterTheOwnerCannotReadIsRefused() {
        // U+202E is a right-to-left override, which would reorder the line the origin is shown on. A control character is refused too.
        assertEquals(AlgoSignRequest.UNREADABLE_ORIGIN, rejectedReason(payload(5L to Cbor.Text("app.example‮"))))
        assertEquals(AlgoSignRequest.UNREADABLE_ORIGIN, rejectedReason(payload(5L to Cbor.Text("app\u0007"))))
        // U+F0000 is private use, one supplementary code point that a character-by-character check reads as two halves.
        assertEquals(AlgoSignRequest.UNREADABLE_ORIGIN, rejectedReason(payload(5L to Cbor.Text("app" + String(Character.toChars(0xF0000))))))
        // A line break or a tab could draw a row that looks like another line of the review, so both are refused.
        assertEquals(AlgoSignRequest.UNREADABLE_ORIGIN, rejectedReason(payload(5L to Cbor.Text("a.example\nType"))))
        assertEquals(AlgoSignRequest.UNREADABLE_ORIGIN, rejectedReason(payload(5L to Cbor.Text("a\tb"))))
        assertEquals("a.example", okOf(AlgoSignRequest.decode(payload(5L to Cbor.Text("a.example")))).origin)
    }

    @Test
    fun aPayloadPastTheLimitIsRefusedBeforeItIsParsed() {
        val tooBig = ByteArray(AlgoSignRequest.MAX_PAYLOAD_BYTES + 1)
        assertTrue(rejectedReason(tooBig).contains("exceeds"))
    }

    @Test
    fun malformedCborIsRefusedAndNoInputThrows() {
        val valid = payload()
        val cases = listOf(
            valid.copyOf(valid.size - 1),
            valid + byteArrayOf(0),
            byteArrayOf(),
            byteArrayOf(0x9f.toByte()),
        )
        for (bytes in cases) {
            assertIs<AlgoSignRequest.Result.Rejected>(AlgoSignRequest.decode(bytes))
        }

        val random = java.util.Random(11)
        repeat(2000) {
            val copy = valid.copyOf(random.nextInt(valid.size + 1))
            if (copy.isNotEmpty()) copy[random.nextInt(copy.size)] = random.nextInt(256).toByte()
            AlgoSignRequest.decode(copy)
        }
    }

    private fun okOf(result: AlgoSignRequest.Result): AlgoSignRequest = assertIs<AlgoSignRequest.Result.Ok>(result).request

    private fun rejectedReason(bytes: ByteArray): String =
        assertIs<AlgoSignRequest.Result.Rejected>(AlgoSignRequest.decode(bytes)).reason

    /** The fields of a valid request, keyed 1 to 6. A null in [changes] removes that key. */
    private fun defaultFields(): Map<Long, Cbor> = linkedMapOf(
        1L to Cbor.Tagged(37, Cbor.Bytes(requestId)),
        2L to Cbor.Bytes(ByteArray(32) { 1 }),
        3L to keypath(),
        4L to Cbor.Text(address),
        5L to Cbor.Text("Lumio"),
        6L to Cbor.Uint(1),
    )

    private fun payload(vararg changes: Pair<Long, Cbor?>): ByteArray {
        val fields = defaultFields().toMutableMap()
        for ((key, value) in changes) {
            if (value == null) fields.remove(key) else fields[key] = value
        }
        return Cbor.encode(Cbor.Dict(fields.map { (key, value) -> Cbor.Uint(key) to value }))
    }

    private fun ledgerComponents(account: Long = 0L): List<Cbor> =
        components(44L to true, 283L to true, account to true, 0L to false, 0L to false)

    /** The flat component list of a keypath: index, hardened flag, index, hardened flag, and so on. */
    private fun components(vararg steps: Pair<Long, Boolean>): List<Cbor> =
        steps.flatMap { (index, hardened) -> listOf(Cbor.Uint(index), Cbor.Bool(hardened)) }

    private fun keypath(components: List<Cbor> = ledgerComponents(), fingerprint: Cbor? = Cbor.Uint(0x73c5da0a)): Cbor =
        Cbor.Tagged(304, dictOf(1L to Cbor.Arr(components), 2L to fingerprint))

    private fun dictOf(vararg fields: Pair<Long, Cbor?>): Cbor.Dict =
        Cbor.Dict(fields.mapNotNull { (key, value) -> value?.let { Cbor.Uint(key) to it } })
}
