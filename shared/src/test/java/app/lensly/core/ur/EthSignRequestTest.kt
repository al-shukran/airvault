package app.lensly.core.ur

import app.lensly.core.parse.ParserLimits
import app.lensly.core.wallet.toHex
import com.sparrowwallet.hummingbird.UR
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.utils.Numeric

class EthSignRequestTest {

    private val requestId = Numeric.hexStringToByteArray("9b1deb4d3b7d4bad9bdd2b0d7b3dcb6d")

    // Legacy transaction RLP from the Keystone vector.
    private val legacyRlp = "f849808609184e72a00082271094000000000000000000000000000000000000000080a47f7465737432" +
        "000000000000000000000000000000000000000000000000000000600057808080"

    @Test
    fun decodesKeystoneRequestScannedAsUr() {
        val scanner = UrScanner()
        assertTrue(scanner.receive(KeystoneVectors.ur("eth-sign-request-origin.txt")))
        val ur = assertNotNull(scanner.result())

        assertFields(okOf(EthSignRequest.decode(ur)), origin = "metamask")
    }

    @Test
    fun decodesKeystoneRequestWithoutOrigin() {
        val scanner = UrScanner()
        assertTrue(scanner.receive(KeystoneVectors.ur("eth-sign-request-no-origin.txt")))
        val request = okOf(EthSignRequest.decode(assertNotNull(scanner.result())))

        assertNull(request.origin)
        assertFields(request, origin = null)
    }

    @Test
    fun knownDataTypesDecodeAndUnknownOnesAreRefused() {
        for (wire in 1..4) {
            assertEquals(wire, okOf(EthSignRequest.decode(payload(dataType = Cbor.Uint(wire.toLong())))).dataType.wire)
        }
        for (wire in listOf(0L, 5L, 255L)) {
            val rejected = assertIs<EthSignRequest.Result.Rejected>(EthSignRequest.decode(payload(dataType = Cbor.Uint(wire))))
            assertTrue(rejected.reason.contains("unsupported dataType"), rejected.reason)
        }
    }

    @Test
    fun acceptsSignDataAtTheLimitAndRefusesOneByteMore() {
        val atLimit = payload(signData = Cbor.Bytes(ByteArray(EthSignRequest.MAX_SIGN_DATA_BYTES)))
        assertEquals(EthSignRequest.MAX_SIGN_DATA_BYTES, okOf(EthSignRequest.decode(atLimit)).signData.size)

        val overLimit = payload(signData = Cbor.Bytes(ByteArray(EthSignRequest.MAX_SIGN_DATA_BYTES + 1)))
        val rejected = assertIs<EthSignRequest.Result.Rejected>(EthSignRequest.decode(overLimit))
        assertTrue(rejected.reason.contains("signData exceeds"), rejected.reason)
    }

    @Test
    fun refusesPayloadsFarOverTheLimitBeforeParsing() {
        val huge = payload(signData = Cbor.Bytes(ByteArray(70_000)))
        val rejected = assertIs<EthSignRequest.Result.Rejected>(EthSignRequest.decode(huge))
        assertTrue(rejected.reason.contains("payload exceeds"), rejected.reason)
    }

    @Test
    fun refusesAPayloadWithTooManyItemsOrTooDeepANestingWithAReason() {
        val crowded = Cbor.encode(Cbor.Dict(List(300) { Cbor.Uint(100L + it) to Cbor.Uint(0) }))
        val tooMany = assertIs<EthSignRequest.Result.Rejected>(EthSignRequest.decode(crowded))
        assertEquals("CBOR has more than ${ParserLimits.MAX_CBOR_ITEMS} items", tooMany.reason)

        var deep: Cbor = Cbor.Uint(0)
        repeat(9) { deep = Cbor.Arr(listOf(deep)) }
        val nested = Cbor.encode(dictOf(2L to deep))
        val tooDeep = assertIs<EthSignRequest.Result.Rejected>(EthSignRequest.decode(nested))
        assertEquals("CBOR nested deeper than ${ParserLimits.MAX_CBOR_DEPTH}", tooDeep.reason)
    }

    @Test
    fun malformedCborIsRejectedWithoutThrowing() {
        val valid = payload()
        val cases = listOf(
            valid.copyOf(valid.size - 1),
            valid + byteArrayOf(0),
            byteArrayOf(),
            byteArrayOf(0x9f.toByte()),
        )
        for (bytes in cases) {
            assertIs<EthSignRequest.Result.Rejected>(EthSignRequest.decode(bytes))
        }
    }

    @Test
    fun schemaViolationsAreRejected() {
        val tagged304 = keypath() as Cbor.Tagged
        val cases = mapOf(
            "missing signData" to payload(signData = null),
            "missing dataType" to payload(dataType = null),
            "missing derivationPath" to payload(path = null),
            "empty signData" to payload(signData = Cbor.Bytes(ByteArray(0))),
            "signData as text" to payload(signData = Cbor.Text("x")),
            "untagged keypath" to payload(path = tagged304.value),
            "keypath with another tag" to payload(path = Cbor.Tagged(305, tagged304.value)),
            "wildcard component" to payload(path = keypath(components = listOf(Cbor.Arr(emptyList()), Cbor.Bool(true)))),
            "odd component count" to payload(path = keypath(components = listOf(Cbor.Uint(44)))),
            "missing hardened flag" to payload(path = keypath(components = listOf(Cbor.Uint(44), Cbor.Uint(1)))),
            "index beyond 31 bits" to payload(path = keypath(components = listOf(Cbor.Uint(1L shl 31), Cbor.Bool(false)))),
            "fingerprint beyond 32 bits" to payload(path = keypath(fingerprint = Cbor.Uint(1L shl 32))),
            "address of 19 bytes" to payload(address = Cbor.Bytes(ByteArray(19))),
            "requestId of 15 bytes" to payload(requestId = Cbor.Tagged(37, Cbor.Bytes(ByteArray(15)))),
            "requestId with another tag" to payload(requestId = Cbor.Tagged(38, Cbor.Bytes(requestId))),
            "origin as bytes" to payload(origin = Cbor.Bytes(byteArrayOf(1))),
            "chainId as text" to payload(chainId = Cbor.Text("1")),
            "payload is an array" to Cbor.encode(Cbor.Arr(emptyList())),
            "duplicate map key" to Cbor.encode(
                Cbor.Dict(listOf(Cbor.Uint(2) to Cbor.Bytes(ByteArray(1)), Cbor.Uint(2) to Cbor.Bytes(ByteArray(1)))),
            ),
        )
        for ((name, bytes) in cases) {
            assertIs<EthSignRequest.Result.Rejected>(EthSignRequest.decode(bytes), message = name)
        }
    }

    @Test
    fun refusesUrOfAnotherType() {
        val ur = UR("eth-signature", payload())
        assertIs<EthSignRequest.Result.Rejected>(EthSignRequest.decode(ur))
    }

    @Test
    fun randomAndMutatedInputNeverThrow() {
        val random = java.util.Random(7)
        repeat(2000) {
            val noise = ByteArray(random.nextInt(96)).also(random::nextBytes)
            EthSignRequest.decode(noise)
        }
        val valid = payload()
        repeat(2000) {
            val copy = valid.copyOf(random.nextInt(valid.size + 1))
            if (copy.isNotEmpty()) copy[random.nextInt(copy.size)] = random.nextInt(256).toByte()
            EthSignRequest.decode(copy)
        }
    }

    private fun okOf(result: EthSignRequest.Result): EthSignRequest =
        assertIs<EthSignRequest.Result.Ok>(result).request

    private fun assertFields(request: EthSignRequest, origin: String?) {
        assertContentEquals(requestId, request.requestId)
        assertEquals(legacyRlp, request.signData.toHex())
        assertEquals(DataType.TRANSACTION, request.dataType)
        assertEquals(1L, request.chainId)
        assertEquals(
            listOf(
                PathStep(44, true),
                PathStep(1, true),
                PathStep(1, true),
                PathStep(0, false),
                PathStep(1, false),
            ),
            request.derivationPath,
        )
        assertEquals(0x12345678, request.sourceFingerprint)
        assertNull(request.address)
        assertEquals(origin, request.origin)
    }

    /** A valid request. Pass null for a field to leave it out. */
    private fun payload(
        requestId: Cbor? = Cbor.Tagged(37, Cbor.Bytes(this.requestId)),
        signData: Cbor? = Cbor.Bytes(Numeric.hexStringToByteArray(legacyRlp)),
        dataType: Cbor? = Cbor.Uint(1),
        chainId: Cbor? = Cbor.Uint(1),
        path: Cbor? = keypath(),
        address: Cbor? = null,
        origin: Cbor? = Cbor.Text("metamask"),
    ): ByteArray = Cbor.encode(
        dictOf(1L to requestId, 2L to signData, 3L to dataType, 4L to chainId, 5L to path, 6L to address, 7L to origin),
    )

    private fun keypath(
        components: List<Cbor> = listOf(
            Cbor.Uint(44), Cbor.Bool(true),
            Cbor.Uint(1), Cbor.Bool(true),
            Cbor.Uint(1), Cbor.Bool(true),
            Cbor.Uint(0), Cbor.Bool(false),
            Cbor.Uint(1), Cbor.Bool(false),
        ),
        fingerprint: Cbor? = Cbor.Uint(0x12345678),
    ): Cbor = Cbor.Tagged(304, dictOf(1L to Cbor.Arr(components), 2L to fingerprint))

    private fun dictOf(vararg fields: Pair<Long, Cbor?>): Cbor.Dict =
        Cbor.Dict(fields.mapNotNull { (key, value) -> value?.let { Cbor.Uint(key) to it } })
}
