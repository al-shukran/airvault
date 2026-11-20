package app.lensly.ui.flow

import app.airvault.chains.evm.evmManifest
import app.airvault.chains.evm.signer.EvmRequestBody
import app.airvault.chains.evm.signer.EvmSigner
import app.airvault.chains.evm.signer.UnsignedTx
import app.airvault.core.model.Approval
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.EcdsaSignature
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.Path
import app.airvault.core.signer.ScopedKeyAccess
import app.lensly.app.EVM_FAMILY
import app.lensly.app.export.PairingExport
import app.lensly.app.export.partsOf
import app.lensly.app.sign.AppPrepared
import app.lensly.app.sign.AppReviewEntry
import app.lensly.app.sign.Prepared
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.SignPipeline
import app.lensly.app.sign.WalletView
import app.lensly.app.sign.evmCounterparties
import app.lensly.app.sign.evmParsedOf
import app.lensly.app.sign.evmReplyOf
import app.lensly.core.parse.CANNOT_BE_READ
import app.lensly.core.parse.DecodeClock
import app.lensly.core.parse.ParserLimits
import app.lensly.core.parse.RequestParser
import app.lensly.core.parse.SandboxedParser
import app.lensly.core.parse.ScanReply
import app.lensly.core.parse.ScriptedSandbox
import app.lensly.core.parse.inProcessSandboxParser
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.Registry
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.UrScanner
import app.lensly.core.ur.framesOf
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import app.lensly.core.wallet.SingleKeyAccess
import app.lensly.core.wallet.WalletKeyAccess
import com.sparrowwallet.hummingbird.UR
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MONAD_TESTNET = 10143L
private const val ART_ADDRESS = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb"
private const val RECIPIENT = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
private const val WALLET_ID = "wallet-mnemonic"
private const val OTHER_ID = "wallet-other"
private const val FRAGMENT = 200

/** The smallest fragment that keeps a request of the largest data to sign within [ParserLimits.MAX_UR_PARTS]: the most parts it can have. */
private const val FRAGMENT_MOST_PARTS = 65

/** Bounds a silent sandbox in a test, so a missing timeout fails the test instead of hanging it. */
private const val SILENCE_BOUND_SECONDS = 10L
private val REQUEST_ID = Numeric.hexStringToByteArray("9b1deb4d3b7d4bad9bdd2b0d7b3dcb6d")
private val SEED by lazy { Mnemonic.toSeed(List(23) { "abandon" } + "art") }
private val WALLET by lazy { HdWallet(SEED) }
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

/** The vault's wallet, as the pipeline sees it. Two accounts. Its fingerprint is the test wallet's. */
private val VIEW by lazy {
    WalletView(
        id = WALLET_ID,
        masterFingerprint = WALLET.masterFingerprint,
        accountCount = 2,
        autoNameBase = "Seed Wallet",
        addressAt = { _, index -> AccountAddresses.addressAt(XPUB, index) },
    )
}

/**
 * Fakes for the app's side of the flow. The review is the real pipeline's, and the signature is the real [EvmSigner]'s over the key access
 * [keysOf] hands out: the keys of the "abandon ... art" wallet, unless a test gives another. [wallets] is what the vault holds.
 */
private class FakePorts(
    private val wallets: List<WalletView> = listOf(VIEW),
    private val passwordCheck: suspend (CharArray) -> PasswordCheck = { PasswordCheck.Accepted },
    private val keysOf: () -> KeyAccess = { WalletKeyAccess(SEED, accountCount = 2) },
    private val unreadable: Boolean = false,
    private val recordFails: Boolean = false,
    private val prepareGate: CompletableDeferred<Unit>? = null,
    private val signGate: CompletableDeferred<Unit>? = null,
    private val parser: RequestParser = inProcessSandboxParser,
) : SignFlowPorts {
    private val pipeline = SignPipeline(REGISTRY, parser)

    val keyWalletIds = mutableListOf<String>()
    val records = mutableListOf<AppReviewEntry>()
    var beforeRecord: (AppReviewEntry) -> Unit = {}
    var keyRequests = 0
    var signCalls = 0

    override suspend fun wallets(): List<WalletView> = wallets

    override suspend fun scan(parts: List<String>): ScanReply = parser.scan(parts)

    override suspend fun privateKey(walletId: String, accountIndex: Int): ByteArray = error("The sign flow reads no private key.")

    override suspend fun checkPassword(password: CharArray): PasswordCheck = passwordCheck(password)

    override suspend fun appPrepare(walletId: String, request: AppRequest): AppPrepared {
        prepareGate?.await()
        val view = wallets.firstOrNull { it.id == walletId } ?: throw WalletUnavailable()
        return when (val prepared = pipeline.prepare(EvmRequestBody.decode(request.body), view)) {
            is Prepared.Ready -> AppPrepared.Ready(
                walletId = walletId,
                request = request,
                review = prepared.review,
                accountIndex = prepared.accountIndex,
                signer = prepared.signer,
                addressChain = EVM_FAMILY,
                counterparties = evmCounterparties(prepared.review),
                addressNotes = emptyMap(),
            )
            is Prepared.Refused -> AppPrepared.Refused(prepared.reason, prepared.call)
        }
    }

    override suspend fun appSign(ready: AppPrepared.Ready, approval: Approval): List<String> {
        keyRequests++
        keyWalletIds += ready.walletId
        if (unreadable) throw WalletUnavailable()
        signCalls++
        signGate?.await()
        val response = EvmSigner.sign(ready.request, approval, ScopedKeyAccess(evmManifest.keys, keysOf()))
        return partsOf(framesOf(UR(response.urType, response.cbor), DEFAULT_MAX_FRAGMENT))
    }

    override suspend fun recordApp(entry: AppReviewEntry) {
        beforeRecord(entry)
        if (recordFails) throw ReviewNotSaved()
        records += entry
    }
}

/** Key access that counts the signatures it makes, so a test can see whether a key signed anything. */
private class CountingKeys(private val delegate: KeyAccess) : KeyAccess by delegate {
    var signatures = 0

    override fun signEcdsaSecp256k1(path: Path, digest32: ByteArray): EcdsaSignature {
        signatures++
        return delegate.signEcdsaSecp256k1(path, digest32)
    }
}

/** A ur:eth-sign-request for a personal message on account 0, the way a wallet sends it. */
private fun messageRequest(
    signData: ByteArray = "Sign in to Lensly".toByteArray(),
    requestId: ByteArray? = REQUEST_ID,
): UR = UR("eth-sign-request", requestCbor(signData, requestId, dataType = 3))

/** A ur:eth-sign-request for a typed transaction on account 0, the way a wallet sends it. */
private fun transactionRequest(data: String): UR {
    val tx = TransactionEncoder.encode(
        RawTransaction.createTransaction(
            MONAD_TESTNET,
            BigInteger.valueOf(7),
            BigInteger.valueOf(21_000),
            RECIPIENT,
            BigInteger.ZERO,
            data,
            BigInteger.valueOf(1_500_000_000L),
            BigInteger.valueOf(30_000_000_000L),
        ),
    )
    return UR("eth-sign-request", requestCbor(tx, REQUEST_ID, dataType = 4))
}

private fun hexWordOf(value: Int): String = value.toString(16).padStart(64, '0')

private fun addressWordOf(address: String): String = address.removePrefix("0x").lowercase().padStart(64, '0')

/** The calldata of Multicall3 `aggregate3((address,bool,bytes)[])` over [calls], each with [target] and allowFailure false. */
private fun aggregate3DataOf(calls: List<String>, target: String): String {
    // Each tuple: its target, its flag, the offset of its bytes (96), then the bytes: their length and their words.
    val tuples = calls.map { data ->
        val padded = data + "00".repeat((32 - data.length / 2 % 32) % 32)
        addressWordOf(target) + hexWordOf(0) + hexWordOf(96) + hexWordOf(data.length / 2) + padded
    }.map { it.length / 2 to it }
    // The array: its length, the offset of each tuple from the start of the offsets, then the tuples.
    var next = 32 * calls.size
    val offsets = tuples.map { (size, _) -> hexWordOf(next).also { next += size } }
    return "0x82ad56cb" + hexWordOf(32) + hexWordOf(calls.size) + offsets.joinToString("") +
        tuples.joinToString("") { it.second }
}

private fun requestCbor(signData: ByteArray, requestId: ByteArray?, dataType: Long): ByteArray {
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
        add(Cbor.Uint(3) to Cbor.Uint(dataType))
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

/** A sandbox that adds one wei to the value of the transaction it decodes, as a compromised sandbox could. */
private class ValueChangingParser : RequestParser by inProcessSandboxParser {
    override fun scan(parts: List<String>): ScanReply {
        val reply = inProcessSandboxParser.scan(parts)
        val parsed = evmParsedOf(reply) ?: return reply
        val tx = assertIs<UnsignedTx.Result.Ok>(parsed.tx).tx
        return evmReplyOf(parsed.copy(tx = UnsignedTx.Result.Ok(tx.copy(value = tx.value + BigInteger.ONE))))
    }
}

/** Scans a complete request through [parser]. The flow refuses it with the sandbox's refusal, and nothing is signed. */
private fun assertRefusedWithoutSigning(parser: RequestParser) {
    val ports = FakePorts(parser = parser)
    val flow = SignFlow(ports, WALLET_ID)
    scan(flow, framesOf(messageRequest(), FRAGMENT))
    assertEquals(SignStep.Refused(CANNOT_BE_READ), flow.state.value)
    assertEquals(0, ports.signCalls)
    assertEquals(0, ports.keyRequests)
}

/** Scans a complete single-code request and returns the review it reaches. */
private fun reviewOf(flow: SignFlow): SignStep.AppReview {
    scan(flow, framesOf(messageRequest(), FRAGMENT))
    return assertIs<SignStep.AppReview>(flow.state.value)
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

class SignFlowTest {
    // These tests are about the flow, not the decode budget (CborTest and RlpTest test the budget with a clock of their own). A slow runner
    // must not refuse a decode here, so the decode clock stands still for each test.
    private val wallClock = DecodeClock.nanoTime

    @Before
    fun stillDecodeClock() {
        DecodeClock.nanoTime = { 0L }
    }

    @After
    fun restoreDecodeClock() {
        DecodeClock.nanoTime = wallClock
    }

    @Test
    fun anAnimatedRequestShowsProgressUntilItIsComplete() {
        val flow = SignFlow(FakePorts(), WALLET_ID)
        val frames = framesOf(messageRequest(signData = ByteArray(300) { 9 }), FRAGMENT)
        runBlocking { flow.onScanned(frames.first()) }
        val partway = assertIs<SignStep.Scanning>(flow.state.value)
        val progress = assertNotNull(partway.progress)
        assertTrue(progress in 0f..1f, "progress was $progress")

        scan(flow, frames.drop(1))
        assertIs<SignStep.AppReview>(flow.state.value)
    }

    @Test
    fun aDifferentKindOfCodeIsRefusedAsUnsupported() {
        val flow = SignFlow(FakePorts(), WALLET_ID)
        val otherCode = PairingExport.urParts(listOf(XPUB), WALLET.masterFingerprint, "Lensly")
        scan(flow, otherCode.asSequence())
        assertEquals(SignStep.Refused("Unsupported code"), flow.state.value)
    }

    @Test
    fun aRequestWithoutAnIdIsRefused() {
        val flow = SignFlow(FakePorts(), WALLET_ID)
        scan(flow, framesOf(messageRequest(requestId = null), FRAGMENT))
        assertEquals(SignStep.Refused("Request has no ID"), flow.state.value)
    }

    @Test
    fun aCodeThatIsNotPartOfTheRequestKeepsScanning() {
        val flow = SignFlow(FakePorts(), WALLET_ID)
        runBlocking { flow.onScanned("https://example.com") }
        assertEquals(null, assertIs<SignStep.Scanning>(flow.state.value).progress)
    }

    @Test
    fun aPartThatDeclaresTooManyPartsIsNotPartOfTheRequest() {
        val flow = SignFlow(FakePorts(), WALLET_ID)
        val declared = "ur:eth-sign-request/1-${ParserLimits.MAX_UR_PARTS + 1}/aa"
        runBlocking { flow.onScanned(declared) }
        assertEquals(null, assertIs<SignStep.Scanning>(flow.state.value).progress)
    }

    @Test
    fun aStrayCodeOverTheLimitsDoesNotEndTheScan() {
        val flow = SignFlow(FakePorts(), WALLET_ID)
        val frames = framesOf(messageRequest(signData = ByteArray(300) { 9 }), FRAGMENT)
        val stray = "ur:eth-sign-request/1-${ParserLimits.MAX_UR_PARTS + 1}/aa"
        runBlocking { flow.onScanned(frames.first()) }
        runBlocking { flow.onScanned(stray) }
        assertIs<SignStep.Scanning>(flow.state.value)
        scan(flow, frames.drop(1))
        assertIs<SignStep.AppReview>(flow.state.value)
    }

    @Test
    fun aRequestOverTheScanLimitIsRefused() {
        val flow = SignFlow(FakePorts(), WALLET_ID)
        scan(flow, framesOf(messageRequest(signData = ByteArray(120 * 1024) { 1 }), FRAGMENT))
        assertEquals("The request is larger than 232 KiB.", assertIs<SignStep.Refused>(flow.state.value).reason)
    }

    @Test
    fun aRequestOfTheLargestSizeTheParserReadsIsNotRefusedAsTooLarge() {
        val flow = SignFlow(FakePorts(), WALLET_ID)
        scan(flow, framesOf(messageRequest(signData = ByteArray(ParserLimits.MAX_SIGN_DATA_BYTES) { 1 }), FRAGMENT))
        assertIs<SignStep.AppReview>(flow.state.value)
    }

    @Test
    fun aMaximumSizeRequestInTheMostPartsAUrMayDeclareIsNotRefusedAsTooLarge() {
        val flow = SignFlow(FakePorts(), WALLET_ID)
        scan(flow, framesOf(messageRequest(signData = ByteArray(ParserLimits.MAX_SIGN_DATA_BYTES) { 1 }), FRAGMENT_MOST_PARTS))
        assertIs<SignStep.AppReview>(flow.state.value)
    }

    @Test
    fun aSixteenKiBRequestIsStillReadWhenHalfOfItsFramesAreMissed() {
        val flow = SignFlow(FakePorts(), WALLET_ID)
        val data = "0xdeadbeef" + "00".repeat(ParserLimits.MAX_CALLDATA_BYTES - 4)
        val frames = framesOf(transactionRequest(data), FRAGMENT).filterIndexed { index, _ -> index % 2 == 0 }
        scan(flow, frames)
        // The call is refused for being undecodable, and shown in full. The scan limit does not stop it.
        assertEquals(512, assertNotNull(assertIs<SignStep.Refused>(flow.state.value).call).words.size)
    }

    @Test
    fun aRouterSizedMulticallOfAboutSixKiBIsScannedInFramesAndReviewed() {
        val flow = SignFlow(FakePorts(), WALLET_ID)
        val transfer = "a9059cbb" + addressWordOf(RECIPIENT) + hexWordOf(1_500_000)
        scan(flow, framesOf(transactionRequest(aggregate3DataOf(List(24) { transfer }, RECIPIENT)), FRAGMENT))
        assertIs<SignStep.AppReview>(flow.state.value)
    }

    @Test
    fun aSixteenKiBCallThatCannotBeDecodedIsScannedInFramesAndShownInFull() {
        val flow = SignFlow(FakePorts(), WALLET_ID)
        val data = "0xdeadbeef" + "00".repeat(ParserLimits.MAX_CALLDATA_BYTES - 4)
        scan(flow, framesOf(transactionRequest(data), FRAGMENT))
        val refused = assertIs<SignStep.Refused>(flow.state.value)
        // The whole call is shown, so a 16 KiB request is not cut short by the scan size limit.
        assertEquals(512, assertNotNull(refused.call).words.size)
    }

    @Test
    fun aRequestWithCalldataOver16KiBIsRefusedWithItsReason() {
        val flow = SignFlow(FakePorts(), WALLET_ID)
        scan(flow, framesOf(transactionRequest("0x" + "00".repeat(ParserLimits.MAX_CALLDATA_BYTES + 1)), FRAGMENT))
        assertEquals(
            "calldata is larger than ${ParserLimits.MAX_CALLDATA_BYTES} bytes",
            assertIs<SignStep.Refused>(flow.state.value).reason,
        )
    }

    @Test
    fun aSandboxThatDiesRefusesTheRequestAndSignsNothing() {
        assertRefusedWithoutSigning(SandboxedParser(ScriptedSandbox { _, _ -> throw IllegalStateException("The sandbox died.") }))
    }

    @Test
    fun aSandboxThatNeverAnswersRefusesTheRequestAndSignsNothing() {
        val release = CountDownLatch(1)
        try {
            val silent = ScriptedSandbox { _, _ ->
                release.await(SILENCE_BOUND_SECONDS, TimeUnit.SECONDS)
                ByteArray(0)
            }
            assertRefusedWithoutSigning(SandboxedParser(silent, timeoutMillis = 50))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun aSandboxThatChangesTheTransactionRefusesTheRequestAndSignsNothing() {
        val ports = FakePorts(parser = ValueChangingParser())
        val flow = SignFlow(ports, WALLET_ID)

        scan(flow, framesOf(transactionRequest("0x"), FRAGMENT))

        assertEquals(SignStep.Refused(CANNOT_BE_READ), flow.state.value)
        assertEquals(0, ports.signCalls)
        assertEquals(0, ports.keyRequests)
    }

    @Test
    fun aMalformedSandboxReplyRefusesTheRequestAndSignsNothing() {
        assertRefusedWithoutSigning(SandboxedParser(ScriptedSandbox { _, _ -> byteArrayOf(1, 2, 3) }))
    }

    @Test
    fun aWrongPasswordLeavesTheConfirmationWithNothingSigned() {
        val password = "wrong pass".toCharArray()
        val ports = FakePorts(passwordCheck = { PasswordCheck.Rejected })
        val flow = SignFlow(ports, WALLET_ID)
        val review = reviewOf(flow)
        flow.approve()

        runBlocking { flow.confirm(password) }

        val waiting = assertIs<SignStep.AppConfirming>(flow.state.value)
        assertEquals(false, waiting.busy)
        assertEquals(review.ready, waiting.ready)
        assertEquals(0, ports.keyRequests)
        assertEquals(0, ports.signCalls)
        assertTrue(password.all { it == '\u0000' }, "the typed password must be zeroed")
    }

    @Test
    fun aRightPasswordSignsWithTheReviewedWalletAndEmitsTheSignatureFrames() {
        val ports = FakePorts()
        val flow = SignFlow(ports, WALLET_ID)
        reviewOf(flow)
        flow.approve()

        runBlocking { flow.confirm("right pass".toCharArray()) }

        val signature = assertIs<SignStep.Signature>(flow.state.value)
        val fields = responseFields(signature.parts)
        assertContentEquals(REQUEST_ID, ((fields.getValue(1L) as Cbor.Tagged).value as Cbor.Bytes).value)
        assertEquals(1, ports.signCalls)
        assertEquals(listOf(WALLET_ID), ports.keyWalletIds)
    }

    /** The key of account 1 answers for the path of account 0, the account the request names, so nothing is signed for the review. */
    @Test
    fun aKeyThatDoesNotControlTheReviewedAddressIsRefusedAndSignsNothing() {
        val keys = CountingKeys(SingleKeyAccess(WALLET.privateKey(1), EvmSigner.singleKeyPath))
        val ports = FakePorts(keysOf = { keys })
        val flow = SignFlow(ports, WALLET_ID)
        reviewOf(flow)
        flow.approve()

        runBlocking { flow.confirm("pass".toCharArray()) }

        assertEquals(SignStep.Refused("Key does not match account"), flow.state.value)
        assertEquals(0, keys.signatures)
    }

    @Test
    fun anUnavailableVaultRefusesWithoutLoadingAKey() {
        val ports = FakePorts(passwordCheck = { PasswordCheck.Unavailable })
        val flow = SignFlow(ports, WALLET_ID)
        reviewOf(flow)
        flow.approve()

        runBlocking { flow.confirm("pass".toCharArray()) }

        assertIs<SignStep.Refused>(flow.state.value)
        assertEquals(0, ports.keyRequests)
    }

    @Test
    fun aWalletThatCannotBeReadRefusesBeforeSigning() {
        val ports = FakePorts(unreadable = true)
        val flow = SignFlow(ports, WALLET_ID)
        reviewOf(flow)
        flow.approve()

        runBlocking { flow.confirm("pass".toCharArray()) }

        assertIs<SignStep.Refused>(flow.state.value)
        assertEquals(0, ports.signCalls)
    }

    @Test
    fun backFromTheConfirmationReturnsToTheReviewAndBackAgainLeaves() {
        val flow = SignFlow(FakePorts(), WALLET_ID)
        val review = reviewOf(flow)
        flow.approve()

        runBlocking { flow.back() }
        assertEquals(review, flow.state.value)

        runBlocking { flow.back() }
        assertEquals(SignStep.Closed, flow.state.value)
    }

    @Test
    fun leavingWhilePasswordIsCheckedDiscardsTheSignature() {
        val answer = CompletableDeferred<PasswordCheck>()
        val ports = FakePorts(passwordCheck = { answer.await() })
        val flow = SignFlow(ports, WALLET_ID)
        reviewOf(flow)
        flow.approve()

        runBlocking {
            val confirming = launch { flow.confirm("pass".toCharArray()) }
            yield()
            assertEquals(true, assertIs<SignStep.AppConfirming>(flow.state.value).busy)
            flow.leave()
            answer.complete(PasswordCheck.Accepted)
            confirming.join()
        }

        assertEquals(SignStep.Closed, flow.state.value)
        assertEquals(0, ports.keyRequests)
        assertEquals(0, ports.signCalls)
    }

    @Test
    fun aGlobalScanSignsWithTheKeyOfTheWalletItFound() {
        val ports = FakePorts()
        val flow = SignFlow(ports, walletId = null)
        reviewOf(flow)
        flow.approve()

        runBlocking { flow.confirm("right pass".toCharArray()) }

        assertIs<SignStep.Signature>(flow.state.value)
        assertEquals(listOf(WALLET_ID), ports.keyWalletIds)
        assertEquals(1, ports.signCalls)
    }

    @Test
    fun aGlobalScanWithNoMatchingWalletIsRefusedInPlainWords() {
        val other = WalletView(
            id = OTHER_ID,
            masterFingerprint = WALLET.masterFingerprint xor 1,
            accountCount = 1,
            autoNameBase = "Other",
            addressAt = { _, index -> AccountAddresses.addressAt(XPUB, index) },
        )
        val flow = SignFlow(FakePorts(wallets = listOf(other)), walletId = null)

        scan(flow, framesOf(messageRequest(), FRAGMENT))

        assertEquals(SignStep.Refused("No wallet in this vault can sign this request"), flow.state.value)
    }

    @Test
    fun aGlobalScanInAnEmptyVaultHasNoMatch() {
        val flow = SignFlow(FakePorts(wallets = emptyList()), walletId = null)

        scan(flow, framesOf(messageRequest(), FRAGMENT))

        assertEquals(SignStep.Refused("No wallet in this vault can sign this request"), flow.state.value)
    }

    @Test
    fun aGlobalScanRefusesToChooseBetweenTwoWalletsForTheSameRequest() {
        val copy = WalletView(
            id = OTHER_ID,
            masterFingerprint = WALLET.masterFingerprint,
            accountCount = 2,
            autoNameBase = "Copy",
            addressAt = { _, index -> AccountAddresses.addressAt(XPUB, index) },
        )
        val ports = FakePorts(wallets = listOf(VIEW, copy))
        val flow = SignFlow(ports, walletId = null)

        scan(flow, framesOf(messageRequest(), FRAGMENT))

        assertEquals(SignStep.Refused("More than one wallet in this vault can sign this request. Lensly will not guess which one signs."), flow.state.value)
        assertEquals(0, ports.keyRequests)
    }

    @Test
    fun anUndecodableCallIsRefusedAndKeepsItsDetails() {
        val flow = SignFlow(FakePorts(), WALLET_ID)

        // balanceOf(address), which Lensly does not decode. It is refused, and the refusal keeps the whole call.
        val data = "0x70a08231" + "0".repeat(24) + RECIPIENT.removePrefix("0x").lowercase()
        scan(flow, framesOf(transactionRequest(data), FRAGMENT))

        val refused = assertIs<SignStep.Refused>(flow.state.value)
        assertEquals("Unknown Contract Call", refused.reason)
        assertEquals("70a08231", assertNotNull(refused.call).selector)
    }

    @Test
    fun aSignedRequestIsRecordedWithTheReviewTheOwnerSaw() {
        val ports = FakePorts()
        val flow = SignFlow(ports, WALLET_ID)
        val review = reviewOf(flow)
        flow.approve()

        runBlocking { flow.confirm("right pass".toCharArray()) }

        assertIs<SignStep.Signature>(flow.state.value)
        val entry = ports.records.single()
        assertEquals(ReviewOutcome.SIGNED, entry.outcome)
        assertEquals(WALLET_ID, entry.walletId)
        assertEquals(review.ready, entry.reviewed)
        assertNull(entry.refusal)
    }

    @Test
    fun aSignatureIsRecordedBeforeItIsShown() {
        val ports = FakePorts()
        val flow = SignFlow(ports, WALLET_ID)
        reviewOf(flow)
        flow.approve()
        var shownWhileRecording: SignStep? = null
        ports.beforeRecord = { shownWhileRecording = flow.state.value }

        runBlocking { flow.confirm("right pass".toCharArray()) }

        assertIs<SignStep.AppConfirming>(shownWhileRecording)
        assertIs<SignStep.Signature>(flow.state.value)
    }

    @Test
    fun aSignatureIsNotShownWhenItsRecordCannotBeSaved() {
        val ports = FakePorts(recordFails = true)
        val flow = SignFlow(ports, WALLET_ID)
        reviewOf(flow)
        flow.approve()

        runBlocking { flow.confirm("right pass".toCharArray()) }

        assertEquals(
            SignStep.Refused("The review could not be saved, so the signature is not shown."),
            flow.state.value,
        )
        assertTrue(ports.records.isEmpty())
    }

    @Test
    fun aRefusedRequestIsRecordedWithItsReason() {
        val ports = FakePorts()
        val flow = SignFlow(ports, WALLET_ID)

        // balanceOf(address), which Lensly does not decode.
        val data = "0x70a08231" + "0".repeat(24) + RECIPIENT.removePrefix("0x").lowercase()
        scan(flow, framesOf(transactionRequest(data), FRAGMENT))

        val refused = assertIs<SignStep.Refused>(flow.state.value)
        val entry = ports.records.single()
        assertEquals(ReviewOutcome.REFUSED, entry.outcome)
        assertEquals(WALLET_ID, entry.walletId)
        assertEquals(refused.reason, entry.refusal)
        assertNull(entry.reviewed)
    }

    @Test
    fun aRefusalAfterTheReviewKeepsTheFactsTheOwnerSaw() {
        val ports = FakePorts(keysOf = { SingleKeyAccess(WALLET.privateKey(1), EvmSigner.singleKeyPath) })
        val flow = SignFlow(ports, WALLET_ID)
        val review = reviewOf(flow)
        flow.approve()

        runBlocking { flow.confirm("pass".toCharArray()) }

        assertEquals(SignStep.Refused("Key does not match account"), flow.state.value)
        val entry = ports.records.single()
        assertEquals(ReviewOutcome.REFUSED, entry.outcome)
        assertEquals(review.ready, entry.reviewed)
        assertEquals("Key does not match account", entry.refusal)
    }

    @Test
    fun aRequestWithoutAnIdIsRecordedOnlyForTheWalletItIsFor() {
        val perWallet = FakePorts()
        scan(SignFlow(perWallet, WALLET_ID), framesOf(messageRequest(requestId = null), FRAGMENT))
        val entry = perWallet.records.single()
        assertEquals(ReviewOutcome.REFUSED, entry.outcome)
        assertEquals(WALLET_ID, entry.walletId)

        val anyWallet = FakePorts()
        scan(SignFlow(anyWallet, walletId = null), framesOf(messageRequest(requestId = null), FRAGMENT))
        assertTrue(anyWallet.records.isEmpty())
    }

    @Test
    fun leavingWhileTheSignatureIsMadeRecordsOnlyTheCancel() {
        val gate = CompletableDeferred<Unit>()
        val ports = FakePorts(signGate = gate)
        val flow = SignFlow(ports, WALLET_ID)
        reviewOf(flow)
        flow.approve()

        runBlocking {
            val signing = launch { flow.confirm("pass".toCharArray()) }
            while (ports.signCalls == 0) yield()
            flow.leave()
            gate.complete(Unit)
            signing.join()
        }

        assertEquals(SignStep.Closed, flow.state.value)
        assertEquals(listOf(ReviewOutcome.CANCELLED), ports.records.map { it.outcome })
    }

    @Test
    fun aReviewedRequestThatIsLeftIsRecordedAsCancelled() {
        val ports = FakePorts()
        val flow = SignFlow(ports, WALLET_ID)
        val review = reviewOf(flow)

        runBlocking { flow.leave() }

        assertEquals(SignStep.Closed, flow.state.value)
        val entry = ports.records.single()
        assertEquals(ReviewOutcome.CANCELLED, entry.outcome)
        assertEquals(review.ready, entry.reviewed)
        assertNull(entry.refusal)
    }

    @Test
    fun aPasswordStepLeftByBackIsRecordedOnlyWhenTheFlowEnds() {
        val ports = FakePorts()
        val flow = SignFlow(ports, WALLET_ID)
        reviewOf(flow)
        flow.approve()

        runBlocking { flow.back() }
        assertTrue(ports.records.isEmpty())

        runBlocking { flow.back() }
        assertEquals(ReviewOutcome.CANCELLED, ports.records.single().outcome)
    }

    @Test
    fun leavingAfterASignatureRecordsNothingMore() {
        val ports = FakePorts()
        val flow = SignFlow(ports, WALLET_ID)
        reviewOf(flow)
        flow.approve()
        runBlocking { flow.confirm("right pass".toCharArray()) }

        runBlocking { flow.leave() }

        assertEquals(listOf(ReviewOutcome.SIGNED), ports.records.map { it.outcome })
    }

    @Test
    fun aWrongPasswordRecordsNothing() {
        val ports = FakePorts(passwordCheck = { PasswordCheck.Rejected })
        val flow = SignFlow(ports, WALLET_ID)
        reviewOf(flow)
        flow.approve()

        runBlocking { flow.confirm("wrong pass".toCharArray()) }

        assertIs<SignStep.AppConfirming>(flow.state.value)
        assertTrue(ports.records.isEmpty())
    }

    @Test
    fun aCodeThatIsNotARequestRecordsNothing() {
        val ports = FakePorts()
        val flow = SignFlow(ports, WALLET_ID)
        val otherCode = PairingExport.urParts(listOf(XPUB), WALLET.masterFingerprint, "Lensly")

        scan(flow, otherCode.asSequence())

        assertEquals(SignStep.Refused("Unsupported code"), flow.state.value)
        assertTrue(ports.records.isEmpty())
    }

    @Test
    fun aRefusalDecidedAfterTheOwnerLeftIsNotRecorded() {
        val gate = CompletableDeferred<Unit>()
        val ports = FakePorts(prepareGate = gate)
        val flow = SignFlow(ports, WALLET_ID)
        // balanceOf(address), which Lensly does not decode. Its refusal is decided only once prepare returns.
        val data = "0x70a08231" + "0".repeat(24) + RECIPIENT.removePrefix("0x").lowercase()
        // The animation repeats its frames forever, so the frames are read lazily, as the scan helper reads them.
        val frames = framesOf(transactionRequest(data), FRAGMENT).take(2000)

        runBlocking {
            val feeding = launch { frames.forEach { flow.onScanned(it) } }
            yield()
            flow.leave()
            gate.complete(Unit)
            feeding.join()
        }

        assertEquals(SignStep.Closed, flow.state.value)
        assertTrue(ports.records.isEmpty())
    }

    @Test
    fun aWalletThatCannotBePreparedForAScanIsRefusedAndRecordedUnderIt() {
        val ports = FakePorts(wallets = emptyList())
        val flow = SignFlow(ports, WALLET_ID)

        scan(flow, framesOf(messageRequest(), FRAGMENT))

        assertEquals(SignStep.Refused("The wallet could not be read. Nothing was signed."), flow.state.value)
        val entry = ports.records.single()
        assertEquals(ReviewOutcome.REFUSED, entry.outcome)
        assertEquals(WALLET_ID, entry.walletId)
        assertEquals("The wallet could not be read. Nothing was signed.", entry.refusal)
    }

    @Test
    fun aLeftRequestWhoseRecordCannotBeSavedStillLeaves() {
        val flow = SignFlow(FakePorts(recordFails = true), WALLET_ID)
        reviewOf(flow)

        runBlocking { flow.leave() }

        assertEquals(SignStep.Closed, flow.state.value)
    }
}
