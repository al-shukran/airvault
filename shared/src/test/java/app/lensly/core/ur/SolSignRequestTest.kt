package app.lensly.core.ur

import app.airvault.chains.solana.signer.SolCluster
import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.lensly.core.wallet.toHex
import com.sparrowwallet.hummingbird.UR
import org.junit.Test
import org.web3j.utils.Numeric
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SolSignRequestTest {

    private val requestId = Numeric.hexStringToByteArray("9b1deb4d3b7d4bad9bdd2b0d7b3dcb6d")

    // The sol-sign-request CBOR and its signData, copied from KeystoneHQ/keystone-sdk-web src/__tests__/solana.test.ts
    // (generateSignRequest, commit 7192bd95). A Solflare transaction at m/44'/501'/0'/0' with source fingerprint 12121212.
    private val keystoneCbor = "a601d825509b1deb4d3b7d4bad9bdd2b0d7b3dcb6d02589601000103c8d842a2f17fd7aab608ce2ea535a6e958dffa20caf669b347b911c4171965530f957620b228bae2b94c82ddd4c093983a67365555b737ec7ddc1117e61c72e0000000000000000000000000000000000000000000000000000000000000000010295cc2f1f39f3604718496ea00676d6a72ec66ad09d926e3ece34f565f18d201020200010c0200000000e1f5050000000003d90130a20188182cf51901f5f500f500f5021a1212121204400568736f6c666c6172650601"
    private val keystoneSignData = "01000103c8d842a2f17fd7aab608ce2ea535a6e958dffa20caf669b347b911c4171965530f957620b228bae2b94c82ddd4c093983a67365555b737ec7ddc1117e61c72e0000000000000000000000000000000000000000000000000000000000000000010295cc2f1f39f3604718496ea00676d6a72ec66ad09d926e3ece34f565f18d201020200010c0200000000e1f50500000000"

    @Test
    fun decodesTheKeystoneSampleToItsFields() {
        val ur = UR(SolSignRequest.UR_TYPE, Numeric.hexStringToByteArray(keystoneCbor))
        val request = okOf(SolSignRequest.decode(ur))

        assertContentEquals(requestId, request.requestId)
        assertEquals(keystoneSignData, request.signData.toHex())
        assertEquals(
            listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true), PathStep(0, true)),
            request.derivationPath,
        )
        assertEquals(0x12121212, request.sourceFingerprint)
        assertNull(request.address)
        assertEquals("solflare", request.origin)
        assertEquals(SolSignType.TRANSACTION, request.signType)
    }

    @Test
    fun signTypeOneAndTwoDecodeAndOtherValuesAreRefused() {
        assertEquals(SolSignType.TRANSACTION, okOf(SolSignRequest.decode(payload(signType = Cbor.Uint(1)))).signType)
        assertEquals(SolSignType.MESSAGE, okOf(SolSignRequest.decode(payload(signType = Cbor.Uint(2)))).signType)
        for (wire in listOf(0L, 3L, 255L)) {
            assertTrue(rejectedReason(payload(signType = Cbor.Uint(wire))).contains("unsupported signType"), "signType $wire")
        }
    }

    @Test
    fun signDataDerivationPathAndSignTypeAreRequired() {
        val cases = mapOf(
            "signData" to payload(signData = null),
            "derivationPath" to payload(path = null),
            "signType" to payload(signType = null),
        )
        for ((name, bytes) in cases) {
            assertTrue(rejectedReason(bytes).contains("$name is missing"), name)
        }
    }

    @Test
    fun requestIdOriginAndAddressMayBeLeftOut() {
        val request = okOf(SolSignRequest.decode(payload(requestId = null, address = null, origin = null)))

        assertNull(request.requestId)
        assertNull(request.address)
        assertNull(request.origin)
    }

    @Test
    fun anAddressIsEmptyOrThirtyTwoBytes() {
        // Keystone's sample sends an empty byte string when it has no address. That reads as no address.
        assertNull(okOf(SolSignRequest.decode(payload(address = Cbor.Bytes(ByteArray(0))))).address)

        val key = ByteArray(32) { (it + 1).toByte() }
        assertContentEquals(key, okOf(SolSignRequest.decode(payload(address = Cbor.Bytes(key)))).address)

        for (size in listOf(1, 20, 31, 33)) {
            assertTrue(rejectedReason(payload(address = Cbor.Bytes(ByteArray(size)))).contains("address must be 32 bytes"), "$size bytes")
        }
    }

    @Test
    fun aPathIsHardenedAndStartsAtSolanasPurposeAndCoinType() {
        val account = okOf(SolSignRequest.decode(payload(path = keypath(components(44L to true, 501L to true, 7L to true)))))
        assertEquals(listOf(PathStep(44, true), PathStep(501, true), PathStep(7, true)), account.derivationPath)

        val refusals = mapOf(
            "a non-hardened step" to components(44L to true, 501L to true, 0L to false),
            "Ethereum's coin type" to components(44L to true, 60L to true, 0L to true),
            "a purpose of 49" to components(49L to true, 501L to true, 0L to true),
            "a path of one step" to components(44L to true),
            "no steps" to emptyList(),
        )
        for ((name, path) in refusals) {
            assertIs<SolSignRequest.Result.Rejected>(SolSignRequest.decode(payload(path = keypath(path))), name)
        }
    }

    @Test
    fun signDataIsNonEmptyAndAtMostTheLimit() {
        assertTrue(rejectedReason(payload(signData = Cbor.Bytes(ByteArray(0)))).contains("signData is empty"))

        val atLimit = payload(signData = Cbor.Bytes(ByteArray(SolSignRequest.MAX_SIGN_DATA_BYTES)))
        assertEquals(SolSignRequest.MAX_SIGN_DATA_BYTES, okOf(SolSignRequest.decode(atLimit)).signData.size)

        val overLimit = payload(signData = Cbor.Bytes(ByteArray(SolSignRequest.MAX_SIGN_DATA_BYTES + 1)))
        assertTrue(rejectedReason(overLimit).contains("signData exceeds"))
    }

    @Test
    fun aRequestIdIsSixteenBytesUnderTag37() {
        assertTrue(rejectedReason(payload(requestId = Cbor.Tagged(37, Cbor.Bytes(ByteArray(15))))).contains("16 bytes"))
        assertTrue(rejectedReason(payload(requestId = Cbor.Tagged(38, Cbor.Bytes(requestId)))).contains("tag 37"))
    }

    @Test
    fun anOriginMustBeText() {
        assertTrue(rejectedReason(payload(origin = Cbor.Bytes(byteArrayOf(1)))).contains("origin must be text"))
    }

    @Test
    fun aClusterIsOptionalAndTheThreeSolanaClustersDecode() {
        assertNull(okOf(SolSignRequest.decode(payload())).cluster)

        assertEquals(SolCluster.MAINNET_BETA, okOf(SolSignRequest.decode(payload(cluster = Cbor.Text("mainnet-beta")))).cluster)
        assertEquals(SolCluster.DEVNET, okOf(SolSignRequest.decode(payload(cluster = Cbor.Text("devnet")))).cluster)
        assertEquals(SolCluster.TESTNET, okOf(SolSignRequest.decode(payload(cluster = Cbor.Text("testnet")))).cluster)
    }

    @Test
    fun aClusterNameLensDoesNotHoldIsRefused() {
        assertTrue(rejectedReason(payload(cluster = Cbor.Text("localnet"))).contains("unsupported cluster"))
        assertTrue(rejectedReason(payload(cluster = Cbor.Text(""))).contains("unsupported cluster"))
        assertTrue(rejectedReason(payload(cluster = Cbor.Uint(2))).contains("cluster must be text"))
    }

    @Test
    fun aUrOfAnotherTypeIsRefused() {
        assertIs<SolSignRequest.Result.Rejected>(SolSignRequest.decode(UR("sol-signature", payload())))
        assertIs<SolSignRequest.Result.Rejected>(SolSignRequest.decode(UR("eth-sign-request", payload())))
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
            assertIs<SolSignRequest.Result.Rejected>(SolSignRequest.decode(bytes))
        }

        val random = java.util.Random(7)
        repeat(2000) {
            val copy = valid.copyOf(random.nextInt(valid.size + 1))
            if (copy.isNotEmpty()) copy[random.nextInt(copy.size)] = random.nextInt(256).toByte()
            SolSignRequest.decode(copy)
        }
    }

    private fun okOf(result: SolSignRequest.Result): SolSignRequest = assertIs<SolSignRequest.Result.Ok>(result).request

    private fun rejectedReason(bytes: ByteArray): String = assertIs<SolSignRequest.Result.Rejected>(SolSignRequest.decode(bytes)).reason

    /** A valid request. Pass null for a field to leave it out. */
    private fun payload(
        requestId: Cbor? = Cbor.Tagged(37, Cbor.Bytes(this.requestId)),
        signData: Cbor? = Cbor.Bytes(ByteArray(32) { 1 }),
        path: Cbor? = keypath(),
        address: Cbor? = null,
        origin: Cbor? = Cbor.Text("solflare"),
        signType: Cbor? = Cbor.Uint(1),
        cluster: Cbor? = null,
    ): ByteArray = Cbor.encode(
        dictOf(1L to requestId, 2L to signData, 3L to path, 4L to address, 5L to origin, 6L to signType, 7L to cluster),
    )

    /** The flat component list of a keypath: index, hardened flag, index, hardened flag, and so on. */
    private fun components(vararg steps: Pair<Long, Boolean>): List<Cbor> =
        steps.flatMap { (index, hardened) -> listOf(Cbor.Uint(index), Cbor.Bool(hardened)) }

    private fun keypath(
        components: List<Cbor> = components(44L to true, 501L to true, 0L to true, 0L to true),
        fingerprint: Cbor? = Cbor.Uint(0x12121212),
    ): Cbor = Cbor.Tagged(304, dictOf(1L to Cbor.Arr(components), 2L to fingerprint))

    private fun dictOf(vararg fields: Pair<Long, Cbor?>): Cbor.Dict =
        Cbor.Dict(fields.mapNotNull { (key, value) -> value?.let { Cbor.Uint(key) to it } })
}
