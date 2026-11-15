package app.lensly.core.ur

import app.lensly.app.export.partsOf
import com.sparrowwallet.hummingbird.UR
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class EthSignRequestEncoderTest {
    private val requestId = ByteArray(16) { (0x10 + it).toByte() }
    private val payload = byteArrayOf(0x02) + ByteArray(120) { (it * 7).toByte() }

    private fun requestOf(
        signData: ByteArray = payload,
        path: List<PathStep> = listOf(PathStep(44, true), PathStep(60, true), PathStep(0, true), PathStep(0, false), PathStep(3, false)),
    ) = EthSignRequest(
        requestId = requestId,
        signData = signData,
        dataType = DataType.TYPED_TRANSACTION,
        chainId = 11155111L,
        derivationPath = path,
        sourceFingerprint = 0x73c5da0a,
        address = ByteArray(20) { (0xA0 + it).toByte() },
        origin = "Lumio",
    )

    /** The UR that a camera reads from [parts]. */
    private fun scanned(parts: List<String>): UR {
        val scanner = UrScanner()
        parts.forEach { scanner.receive(it) }
        return assertNotNull(scanner.result())
    }

    @Test
    fun aRequestReadsBackToItsFields() {
        val request = requestOf()

        val parsed = assertIs<EthSignRequest.Result.Ok>(
            EthSignRequest.decode(scanned(partsOf(EthSignRequestEncoder.encode(request)))),
        ).request

        assertContentEquals(requestId, parsed.requestId)
        assertContentEquals(payload, parsed.signData)
        assertEquals(DataType.TYPED_TRANSACTION, parsed.dataType)
        assertEquals(11155111L, parsed.chainId)
        assertEquals(request.derivationPath, parsed.derivationPath)
        assertEquals(0x73c5da0a, parsed.sourceFingerprint)
        assertContentEquals(request.address, parsed.address)
        assertEquals("Lumio", parsed.origin)
    }

    @Test
    fun aLargeRequestIsAnAnimatedCodeThatReadsBack() {
        val large = byteArrayOf(0x02) + ByteArray(900) { (it * 13).toByte() }

        val parts = partsOf(EthSignRequestEncoder.encode(requestOf(signData = large), maxFragment = 100))

        assertTrue(parts.size > 1)
        assertContentEquals(large, assertIs<EthSignRequest.Result.Ok>(EthSignRequest.decode(scanned(parts))).request.signData)
    }

    @Test
    fun aRequestThatLenslyWouldRefuseIsNotEncoded() {
        assertFailsWith<IllegalArgumentException> { EthSignRequestEncoder.encode(requestOf(path = emptyList())) }
        assertFailsWith<IllegalArgumentException> { EthSignRequestEncoder.encode(requestOf(signData = ByteArray(0))) }
    }
}
