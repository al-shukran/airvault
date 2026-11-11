package app.lensly.ui.flow

import app.lensly.app.export.PairingExport
import app.lensly.app.sign.Prepared
import app.lensly.app.sign.SignPipeline
import app.lensly.app.sign.WalletView
import app.lensly.core.parse.ParserLimits
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.Registry
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.UrScanner
import app.lensly.core.ur.framesOf
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import com.sparrowwallet.hummingbird.UR
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import org.web3j.utils.Numeric

private const val MONAD_TESTNET = 10143L
private const val ART_ADDRESS = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb"
private const val FRAGMENT = 200
private val REQUEST_ID = Numeric.hexStringToByteArray("9b1deb4d3b7d4bad9bdd2b0d7b3dcb6d")
private val WALLET by lazy { HdWallet(Mnemonic.toSeed(List(23) { "abandon" } + "art")) }
private val XPUB by lazy { WALLET.accountXpub() }

private val REGISTRY = Registry(
    networks = listOf(
        NetworkEntry(
            chainId = MONAD_TESTNET,
            name = "Monad Testnet",
            nativeSymbol = "MON",
            nativeDecimals = 18,
            feeModel = FeeModel.L1,
            explorerName = "MonadVision",
            testnet = true,
        ),
    ),
    tokens = emptyList(),
)

/**
 * Fakes for the app's side of the flow. The pipeline and the key derivation are real: the key comes from the
 * "abandon ... art" wallet, and [keyFor] can hand out a different one.
 */
private class FakePorts(
    private val passwordCheck: suspend (CharArray) -> PasswordCheck = { PasswordCheck.Accepted },
    private val keyFor: (Int) -> ByteArray = { WALLET.privateKey(it) },
    private val unreadable: Boolean = false,
) : SignFlowPorts {
    private val pipeline = SignPipeline(REGISTRY)
    private val view = WalletView(WALLET.masterFingerprint, accountCount = 2, autoNameBase = "Seed Wallet") {
        AccountAddresses.addressAt(XPUB, it)
    }

    val keysHandedOut = mutableListOf<ByteArray>()
    var keyRequests = 0
    var signCalls = 0

    override suspend fun prepare(request: EthSignRequest): Prepared = pipeline.prepare(request, view)

    override suspend fun privateKey(accountIndex: Int): ByteArray {
        keyRequests++
        if (unreadable) throw WalletUnavailable()
        return keyFor(accountIndex).also { keysHandedOut += it }
    }

    override suspend fun checkPassword(password: CharArray): PasswordCheck = passwordCheck(password)

    override suspend fun sign(ready: Prepared.Ready, privateKey: ByteArray): List<String> {
        signCalls++
        return pipeline.sign(ready, privateKey)
    }
}

/** A ur:eth-sign-request for a personal message on account 0, the way a wallet sends it. */
private fun messageRequest(
    signData: ByteArray = "Sign in to Lensly".toByteArray(),
    requestId: ByteArray? = REQUEST_ID,
): UR = UR("eth-sign-request", requestCbor(signData, requestId))

private fun requestCbor(signData: ByteArray, requestId: ByteArray?): ByteArray {
    val steps = listOf(44L to true, 60L to true, 0L to true, 0L to false, 0L to false)
    val keypath = Cbor.Tagged(
        304,
        Cbor.Dict(
            listOf(
                Cbor.Uint(1) to Cbor.Arr(steps.flatMap { (index, hardened) -> listOf(Cbor.Uint(index), Cbor.Bool(hardened)) }),
                Cbor.Uint(2) to Cbor.Uint(WALLET.masterFingerprint.toLong() and 0xFFFFFFFFL),
            ),
        ),
    )
    val fields = buildList<Pair<Cbor, Cbor>> {
        requestId?.let { add(Cbor.Uint(1) to Cbor.Tagged(37, Cbor.Bytes(it))) }
        add(Cbor.Uint(2) to Cbor.Bytes(signData))
        add(Cbor.Uint(3) to Cbor.Uint(3))
        add(Cbor.Uint(4) to Cbor.Uint(MONAD_TESTNET))
        add(Cbor.Uint(5) to keypath)
    }
    return Cbor.encode(Cbor.Dict(fields))
}

/** Feeds [frames] in order until the flow leaves the scanning step. Returns how many frames were fed. */
private fun scan(flow: SignFlow, frames: Sequence<String>, limit: Int = 2000): Int = runBlocking {
    var fed = 0
    for (frame in frames.take(limit)) {
        fed++
        flow.onScanned(frame)
        if (flow.state.value !is SignStep.Scanning) break
    }
    fed
}

/** Scans a complete single-code request and returns the review it reaches. */
private fun reviewOf(flow: SignFlow): SignStep.Review {
    scan(flow, framesOf(messageRequest(), FRAGMENT))
    return assertIs<SignStep.Review>(flow.state.value)
}

/** The fields of the single ur:eth-signature frame that answers a request, keyed by map key. */
private fun responseFields(parts: List<String>): Map<Long, Cbor> {
    assertEquals(1, parts.size)
    val scanner = UrScanner()
    assertTrue(scanner.receive(parts.single()))
    val ur = assertNotNull(scanner.result())
    assertEquals("eth-signature", ur.type)
    return (Cbor.decode(ur.cborBytes) as Cbor.Dict).entries.associate { (key, value) ->
        (key as Cbor.Uint).value to value
    }
}

private fun assertAllZero(keys: List<ByteArray>) {
    assertTrue(keys.isNotEmpty(), "the flow should have loaded a key")
    keys.forEach { key -> assertTrue(key.all { it == 0.toByte() }, "a key was left in memory") }
}

class SignFlowTest {

    @Test
    fun aCompleteRequestReachesTheReview() {
        val review = reviewOf(SignFlow(FakePorts())).ready.review
        assertEquals(ART_ADDRESS, review.fromAddress)
        assertEquals(MONAD_TESTNET, review.chainId)
        assertEquals("Sign message", review.function)
    }

    @Test
    fun anAnimatedRequestShowsProgressUntilItIsComplete() {
        val flow = SignFlow(FakePorts())
        val frames = framesOf(messageRequest(signData = ByteArray(300) { 9 }), FRAGMENT)
        runBlocking { flow.onScanned(frames.first()) }
        val partway = assertIs<SignStep.Scanning>(flow.state.value)
        val progress = assertNotNull(partway.progress)
        assertTrue(progress in 0f..1f, "progress was $progress")

        scan(flow, frames.drop(1))
        assertIs<SignStep.Review>(flow.state.value)
    }

    @Test
    fun aDifferentKindOfCodeIsRefusedAsUnsupported() {
        val flow = SignFlow(FakePorts())
        val otherCode = PairingExport.urParts(XPUB, WALLET.masterFingerprint, "Lensly").single()
        runBlocking { flow.onScanned(otherCode) }
        assertEquals(SignStep.Refused("Unsupported code"), flow.state.value)
    }

    @Test
    fun aRequestWithoutAnIdIsRefused() {
        val flow = SignFlow(FakePorts())
        scan(flow, framesOf(messageRequest(requestId = null), FRAGMENT))
        assertEquals(SignStep.Refused("Request has no ID"), flow.state.value)
    }

    @Test
    fun aCodeThatIsNotPartOfTheRequestKeepsScanning() {
        val flow = SignFlow(FakePorts())
        runBlocking { flow.onScanned("https://example.com") }
        assertEquals(null, assertIs<SignStep.Scanning>(flow.state.value).progress)
    }

    @Test
    fun aPartThatDeclaresTooManyPartsIsRefusedWithItsReason() {
        val flow = SignFlow(FakePorts())
        val declared = "ur:eth-sign-request/1-${ParserLimits.MAX_UR_PARTS + 1}/aa"
        runBlocking { flow.onScanned(declared) }
        assertEquals(
            "The request has more than ${ParserLimits.MAX_UR_PARTS} parts.",
            assertIs<SignStep.Refused>(flow.state.value).reason,
        )
    }

    @Test
    fun aRequestLargerThan64KiBIsRefusedBeforeItCompletes() {
        val flow = SignFlow(FakePorts())
        val read = scan(flow, framesOf(messageRequest(signData = ByteArray(70 * 1024) { 1 }), FRAGMENT))
        assertEquals("The request is larger than 64 KiB.", assertIs<SignStep.Refused>(flow.state.value).reason)
        assertTrue(read < 300, "refused only after $read frames, so the scan did not stop early")
    }

    @Test
    fun aWrongPasswordLeavesTheConfirmationWithNothingSigned() {
        val password = "wrong pass".toCharArray()
        val ports = FakePorts(passwordCheck = { PasswordCheck.Rejected })
        val flow = SignFlow(ports)
        val review = reviewOf(flow)
        flow.approve()

        runBlocking { flow.confirm(password) }

        val waiting = assertIs<SignStep.Confirming>(flow.state.value)
        assertEquals(false, waiting.busy)
        assertEquals(review.ready, waiting.ready)
        assertEquals(0, ports.keyRequests)
        assertEquals(0, ports.signCalls)
        assertTrue(password.all { it == '\u0000' }, "the typed password must be zeroed")
    }

    @Test
    fun aRightPasswordSignsAndEmitsTheSignatureFrames() {
        val ports = FakePorts()
        val flow = SignFlow(ports)
        reviewOf(flow)
        flow.approve()

        runBlocking { flow.confirm("right pass".toCharArray()) }

        val signature = assertIs<SignStep.Signature>(flow.state.value)
        val fields = responseFields(signature.parts)
        assertContentEquals(REQUEST_ID, ((fields.getValue(1L) as Cbor.Tagged).value as Cbor.Bytes).value)
        assertEquals(1, ports.signCalls)
        assertAllZero(ports.keysHandedOut)
    }

    @Test
    fun aKeyThatDoesNotControlTheReviewedAddressIsRefusedAndZeroed() {
        val ports = FakePorts(keyFor = { WALLET.privateKey(1) })
        val flow = SignFlow(ports)
        reviewOf(flow)
        flow.approve()

        runBlocking { flow.confirm("pass".toCharArray()) }

        assertEquals(SignStep.Refused("Key does not match account"), flow.state.value)
        assertEquals(0, ports.signCalls)
        assertAllZero(ports.keysHandedOut)
    }

    @Test
    fun anUnavailableVaultRefusesWithoutLoadingAKey() {
        val ports = FakePorts(passwordCheck = { PasswordCheck.Unavailable })
        val flow = SignFlow(ports)
        reviewOf(flow)
        flow.approve()

        runBlocking { flow.confirm("pass".toCharArray()) }

        assertIs<SignStep.Refused>(flow.state.value)
        assertEquals(0, ports.keyRequests)
    }

    @Test
    fun aWalletThatCannotBeReadRefusesBeforeSigning() {
        val ports = FakePorts(unreadable = true)
        val flow = SignFlow(ports)
        reviewOf(flow)
        flow.approve()

        runBlocking { flow.confirm("pass".toCharArray()) }

        assertIs<SignStep.Refused>(flow.state.value)
        assertEquals(0, ports.signCalls)
    }

    @Test
    fun backFromTheConfirmationReturnsToTheReviewAndBackAgainLeaves() {
        val flow = SignFlow(FakePorts())
        val review = reviewOf(flow)
        flow.approve()

        flow.back()
        assertEquals(review, flow.state.value)

        flow.back()
        assertEquals(SignStep.Closed, flow.state.value)
    }

    @Test
    fun leavingWhilePasswordIsCheckedDiscardsTheSignature() {
        val answer = CompletableDeferred<PasswordCheck>()
        val ports = FakePorts(passwordCheck = { answer.await() })
        val flow = SignFlow(ports)
        reviewOf(flow)
        flow.approve()

        runBlocking {
            val confirming = launch { flow.confirm("pass".toCharArray()) }
            yield()
            assertEquals(true, assertIs<SignStep.Confirming>(flow.state.value).busy)
            flow.leave()
            answer.complete(PasswordCheck.Accepted)
            confirming.join()
        }

        assertEquals(SignStep.Closed, flow.state.value)
        assertEquals(0, ports.keyRequests)
        assertEquals(0, ports.signCalls)
    }
}
