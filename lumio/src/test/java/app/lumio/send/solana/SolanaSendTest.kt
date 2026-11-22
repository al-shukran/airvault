package app.lumio.send.solana

import app.airvault.chains.solana.signer.SolCluster
import app.airvault.chains.solana.signer.SolSignRequest
import app.airvault.chains.solana.signer.SolSignType
import app.airvault.chains.solana.signer.SolanaMessage
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.solana.SolanaNonce
import app.airvault.chains.solana.signer.SolanaRequestCheck
import app.lensly.core.solana.SolanaTransaction
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.SolSignatureEncoder
import app.lensly.core.ur.UrScanner
import app.lensly.core.wallet.Ed25519Signer
import app.lensly.core.wallet.Mnemonic
import app.lumio.data.MonotonicClock
import app.lumio.data.config.ProviderConfig
import app.lumio.data.provider.Http
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.SolanaAccountState
import app.lumio.data.provider.solana.CustomSolanaRpc
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.data.provider.solana.SOLANA_MAINNET
import app.lumio.data.rpc.JsonRpc
import app.lumio.data.sched.Scheduler
import app.lumio.send.SubmitMachine
import app.lumio.ui.send.solana.SETUP_SIGN_TEXT
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.Base58
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Solana send against the shapes the endpoint gives. The keys come from the public BIP39 test phrase, and the signatures are the ones Lensly
 * would make, with the same derivation, so the checks run on real bytes. Lumio holds no key: the tests sign only to play Lensly's part. No test reaches
 * a network, and waits are recorded rather than slept.
 */

@OptIn(ExperimentalCoroutinesApi::class)
class SolanaSendTest {
    private val server = MockWebServer()
    private val waits = mutableListOf<Long>()
    private val seed = Mnemonic.of(PHRASE.split(' ')).use { it.deriveSeed("") }
    private val accountIndex = 2
    private val sender = SolanaAccount.publicKey(seed, accountIndex)
    private val wallet = listOf(SolanaAccount.publicKey(seed, 0), SolanaAccount.publicKey(seed, 1), sender)
    private val recipient = Base58.decode(RECIPIENT)
    private val blockhash = Base58.decode(BLOCKHASH)
    private val requestId = ByteArray(16) { (it + 1).toByte() }
    private val account = SolanaSendAccount(index = accountIndex, key = sender, sourceFingerprint = 0x0BADF00D)
    private val lamports = 1_000_000_000L
    private val neededBalance = BigInteger.valueOf(lamports + SOLANA_FEE_LAMPORTS + SOLANA_RENT_RESERVE_LAMPORTS)
    private val window = SetupWindow(lastValidBlockHeight = 100L)

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    /** The owner's devnet RPC set to the local server, with the waits recorded. */
    private fun scheduler(): Scheduler = Scheduler(
        providers = listOf(CustomSolanaRpc("devnet", allowLocalHttp = true, pause = { waits += it })),
        configs = { mapOf(CustomSolanaRpc.idFor("devnet") to ProviderConfig(baseUrl = server.url("/").toString())) },
        http = Http(JsonRpc.newHttp(timeoutMs = 2_000L).client),
        clock = MonotonicClock,
        random = Random(4),
        scope = CoroutineScope(Dispatchers.Default),
    )

    private fun answer(result: String) = MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"result":$result}""").build()

    /** A one-time setup of the test account, with a balance that covers the rent. */
    private fun setupPlan(window: SetupWindow): SolanaSendPlan = SolanaSend.setupPlan(
        SOLANA_DEVNET,
        account,
        1_447_680L,
        BigInteger.valueOf(10_000_000_000L),
        blockhash,
        requestId,
        window,
    )

    private fun plan(): SolanaSendPlan = SolanaSend.plan(
        network = SOLANA_DEVNET,
        account = account,
        recipient = recipient,
        lamports = lamports,
        balance = neededBalance,
        nonceValue = blockhash,
        requestId = requestId,
    )

    /** The 32-byte private key of account [index] as Lensly derives it. The caller zeroes it. */
    private fun privateKeyOf(index: Int): ByteArray = SolanaAccount.node(seed, index).use { it.privateKey() }

    /** The UR of Lensly's answer for [signature], read through the scanner as the camera reads it. */
    private fun answerOf(id: ByteArray, signature: ByteArray): UR {
        val scanner = UrScanner()
        assertTrue(scanner.receive(SolSignatureEncoder.encode(id, signature).first()))
        return assertNotNull(scanner.result())
    }

    /** A submit of [plan] whose broadcast failed with [failure], as the route holds it once the owner has submitted it. */
    private fun failedSubmit(plan: SolanaSendPlan, setup: Boolean, failure: Throwable): SolanaSendStep.Submit = runBlocking {
        val machine = SubmitMachine<SolanaSignedSend, String>(
            signed = SolanaSignedSend(raw = ByteArray(0), signature = "sig"),
            broadcast = { throw failure },
            describe = { solanaFailure(it, setup) },
        )
        machine.submit()
        SolanaSendStep.Submit(plan, listOf("part"), machine, setup)
    }

    /** The UR that the frames of a request complete, read as a scanner reads them. */
    private fun urOfParts(parts: List<String>): UR {
        val scanner = UrScanner()
        for (part in parts) assertTrue(scanner.receive(part), part)
        return assertNotNull(scanner.result())
    }

    @Test
    fun aRecipientIsA32ByteAddressOrASolanaLinkThatHoldsOne() {
        assertContentEquals(recipient, SolanaSendInput.recipient("  $RECIPIENT "))
        assertContentEquals(recipient, SolanaSendInput.recipient("solana:$RECIPIENT?amount=1&label=demo"))
        assertFailsWith<IllegalArgumentException> { SolanaSendInput.recipient("") }
        // A zero is not in the base58 alphabet.
        assertFailsWith<IllegalArgumentException> { SolanaSendInput.recipient("0" + RECIPIENT.drop(1)) }
        assertFailsWith<IllegalArgumentException> { SolanaSendInput.recipient(RECIPIENT + "2") }
        // Forty-four characters that decode to 33 bytes are not a key.
        assertFailsWith<IllegalArgumentException> { SolanaSendInput.recipient("z".repeat(44)) }
    }

    @Test
    fun anAmountIsAnExactCountOfLamportsWithAtMostNineDecimals() {
        assertEquals(100_000_000L, SolanaSendInput.lamports("0.1"))
        assertEquals(1_000_000_000L, SolanaSendInput.lamports("1"))
        assertEquals(1L, SolanaSendInput.lamports("0.000000001"))
        assertEquals(1_500_000_000L, SolanaSendInput.lamports(" 1.5 "))
        assertFailsWith<IllegalArgumentException> { SolanaSendInput.lamports("0.0000000001") }
        assertFailsWith<IllegalArgumentException> { SolanaSendInput.lamports("0") }
        assertFailsWith<IllegalArgumentException> { SolanaSendInput.lamports("0.000") }
        assertFailsWith<IllegalArgumentException> { SolanaSendInput.lamports("1e3") }
        assertFailsWith<IllegalArgumentException> { SolanaSendInput.lamports("-1") }
        assertFailsWith<IllegalArgumentException> { SolanaSendInput.lamports("") }
        assertFailsWith<IllegalArgumentException> { SolanaSendInput.lamports("9".repeat(20)) }
    }

    @Test
    fun theAmountIsShownWithoutTrailingZeros() {
        assertEquals("0.00089088", SolanaSendInput.format(BigInteger.valueOf(SOLANA_RENT_RESERVE_LAMPORTS)))
        assertEquals("1", SolanaSendInput.format(BigInteger.valueOf(1_000_000_000L)))
        assertEquals("0", SolanaSendInput.format(BigInteger.ZERO))
    }

    @Test
    fun theMaxLeavesTheFeeAndTheRentReserveInTheSender() {
        assertEquals(1_000_000_000L - SOLANA_FEE_LAMPORTS - SOLANA_RENT_RESERVE_LAMPORTS, SolanaSend.maxLamports(BigInteger.valueOf(1_000_000_000L)))
        // The fee and the reserve are 895880 lamports together, so one more lamport is the only amount that can go.
        assertNull(SolanaSend.maxLamports(BigInteger.valueOf(895_879L)))
        assertNull(SolanaSend.maxLamports(BigInteger.valueOf(895_880L)))
        assertEquals(1L, SolanaSend.maxLamports(BigInteger.valueOf(895_881L)))
    }

    @Test
    fun aSendNeedsTheAmountTheFeeAndTheRentReserveInTheBalance() {
        assertFailsWith<IllegalArgumentException> {
            SolanaSend.plan(SOLANA_DEVNET, account, recipient, lamports, neededBalance - BigInteger.ONE, blockhash, requestId)
        }
        assertFailsWith<IllegalArgumentException> {
            SolanaSend.plan(SOLANA_DEVNET, account, recipient, 0L, neededBalance, blockhash, requestId)
        }

        val exact = plan()

        assertEquals(SOLANA_FEE_LAMPORTS, exact.feeLamports)
        assertContentEquals(SolanaNonce.transferMessage(sender, recipient, lamports, blockhash), exact.message)
        assertIs<SolanaMessage.Result.Ok>(SolanaMessage.decode(exact.message))
    }

    /**
     * The one-time setup pays the rent into the sender's nonce account, which is the plan's recipient, and it names the setup message. The balance
     * must cover the rent, the fee and the reserve.
     */
    @Test
    fun theSetupPaysTheRentIntoTheNonceAccountOfTheSender() {
        val rent = 1_447_680L
        val needed = BigInteger.valueOf(rent + SOLANA_FEE_LAMPORTS + SOLANA_RENT_RESERVE_LAMPORTS)
        assertFailsWith<IllegalArgumentException> {
            SolanaSend.setupPlan(SOLANA_DEVNET, account, rent, needed - BigInteger.ONE, blockhash, requestId, window)
        }

        val setup = SolanaSend.setupPlan(SOLANA_DEVNET, account, rent, needed, blockhash, requestId, window)

        assertEquals(rent, setup.lamports)
        assertEquals(SOLANA_FEE_LAMPORTS, setup.feeLamports)
        assertContentEquals(SolanaNonce.accountOf(account.key), setup.recipient)
        assertContentEquals(SolanaNonce.setupMessage(account.key, blockhash, rent), setup.message)
        assertIs<SolanaMessage.Result.Ok>(SolanaMessage.decode(setup.message))
        assertSame(window, setup.window)
    }

    /** The setup is read at "confirmed", and its window keeps the last valid height and the moment of the fetch. */
    @Test
    fun theSetupIsBuiltAtTheConfirmedBlockhashAndKeepsItsWindow() = runBlocking {
        server.enqueue(answer("""{"context":{"slot":1},"value":{"blockhash":"$BLOCKHASH","lastValidBlockHeight":250}}"""))
        server.enqueue(answer("""{"context":{"slot":1},"value":5000000000}"""))

        val setup = SolanaSend.setupAt(scheduler(), SOLANA_DEVNET, account, 1_447_680L)

        val setupWindow = assertNotNull(setup.window)
        assertEquals(250L, setupWindow.lastValidBlockHeight)
        assertContentEquals(blockhash, setup.blockhash)
        val fetch = server.takeRequest().body?.utf8().orEmpty()
        assertTrue(fetch.contains("\"method\":\"getLatestBlockhash\""))
        assertTrue(fetch.contains("[{\"commitment\":\"confirmed\"}]"))
    }

    /** A blockhash is accepted at its last valid height, and only a later height runs it out. */
    @Test
    fun aBlockhashIsValidAtItsLastValidHeightAndNotAfter() {
        assertFalse(SolanaSend.expired(height = 100L, lastValidBlockHeight = 100L))
        assertTrue(SolanaSend.expired(height = 101L, lastValidBlockHeight = 100L))
    }

    /** Before a setup is sent, the chain's height is read. A height past the last valid one sends nothing, and the words say so. */
    @Test
    fun anExpiredSetupIsNotSentAndSaysSo() = runBlocking {
        server.enqueue(answer("101"))

        val expired = assertFailsWith<BlockhashExpired> {
            SolanaSend.requireUnexpired(scheduler(), SOLANA_DEVNET, SetupWindow(lastValidBlockHeight = 100L))
        }
        val failure = solanaFailure(expired, setup = true)

        assertEquals(SETUP_EXPIRED, failure.message)
        assertFalse(failure.retryable)
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().body?.utf8().orEmpty().contains("\"method\":\"getBlockHeight\""))
    }

    /**
     * Back from a setup that the network refused for its blockhash goes to its review, not its request: the request names the blockhash that aged out, so
     * a signature scanned from it could never land. The review fetches a fresh blockhash when it is shown.
     */
    @Test
    fun aSetupRefusedForItsBlockhashLeavesToItsReview() {
        val plan = setupPlan(window)
        for (refusal in listOf(BlockhashExpired(), ProviderError.Fatal(JsonRpc.BLOCKHASH_REFUSED_MESSAGE))) {
            val review = assertIs<SolanaSendStep.SetupReview>(unsentExit(failedSubmit(plan, setup = true, failure = refusal)))
            assertEquals(plan.recipientAddress, review.nonceAccount)
            assertEquals(plan.lamports, review.rentLamports)
        }
    }

    /**
     * Back from a send that the network refused for its nonce goes to its form, not its request: the request names the nonce that moved, so a
     * signature scanned from it would be refused again. Making the send again reads the nonce that the network holds now.
     */
    @Test
    fun aSendRefusedForItsNonceLeavesToItsForm() {
        assertIs<SolanaSendStep.Form>(
            unsentExit(failedSubmit(plan(), setup = false, failure = ProviderError.Fatal(JsonRpc.BLOCKHASH_REFUSED_MESSAGE))),
        )
    }

    /** A setup that failed for another reason, and a send that failed for another reason, keep their request as before. */
    @Test
    fun otherFailuresLeaveToTheirRequest() {
        assertIs<SolanaSendStep.Request>(unsentExit(failedSubmit(setupPlan(window), setup = true, failure = ProviderError.Transient())))
        assertIs<SolanaSendStep.Request>(unsentExit(failedSubmit(plan(), setup = false, failure = ProviderError.Transient())))
    }

    /** A setup that the owner has not submitted yet goes back to its request, since nothing has been refused. */
    @Test
    fun aSetupNotYetSubmittedLeavesToItsRequest() {
        val machine = SubmitMachine<SolanaSignedSend, String>(
            signed = SolanaSignedSend(raw = ByteArray(0), signature = "sig"),
            broadcast = { it.signature },
            describe = { solanaFailure(it, setup = true) },
        )
        assertIs<SolanaSendStep.Request>(unsentExit(SolanaSendStep.Submit(setupPlan(window), listOf("part"), machine, setup = true)))
    }

    /** A setup still inside its window is checked, and then sent as it was signed. */
    @Test
    fun aSetupInsideItsWindowIsSentAfterTheCheck() = runBlocking {
        val plan = setupPlan(SetupWindow(lastValidBlockHeight = 100L))
        val signed = SolanaSend.signed(plan, answerOf(requestId, Ed25519Signer.sign(privateKeyOf(accountIndex), plan.message)))
        server.enqueue(answer("100"))
        server.enqueue(answer("\"${signed.signature}\""))

        SolanaSend.requireUnexpired(scheduler(), SOLANA_DEVNET, assertNotNull(plan.window))

        assertEquals(signed.signature, SolanaSend.broadcast(scheduler(), SOLANA_DEVNET, signed))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun theOneTimeSetupSaysWhatToDoAndHowSoon() {
        assertEquals("One-time setup. Sign and scan back within about a minute.", SETUP_SIGN_TEXT)
    }

    @Test
    fun theSignRequestIsOneThatLensCanReadAndCheckForTheSendersAccount() {
        val request = assertIs<SolSignRequest.Result.Ok>(SolSignRequest.decode(urOfParts(SolanaSend.requestParts(plan())))).request

        assertContentEquals(requestId, request.requestId)
        assertContentEquals(plan().message, request.signData)
        assertEquals(listOf(PathStep(44, true), PathStep(501, true), PathStep(accountIndex, true)), request.derivationPath)
        assertContentEquals(sender, request.address)
        assertEquals(0x0BADF00D, request.sourceFingerprint)
        assertEquals("Lumio", request.origin)
        assertEquals(SolSignType.TRANSACTION, request.signType)
        assertEquals(SolCluster.DEVNET, request.cluster)
        // Lensly's own check takes the request for account 2 of the wallet's keys, in account order.
        val review = assertIs<SolanaRequestCheck.Result.Ok>(SolanaRequestCheck.check(request, wallet)).review
        assertEquals(accountIndex, review.accountIndex)
        assertEquals(SolCluster.DEVNET, review.cluster)
    }

    /** The request states the cluster of the send's network, so a mainnet send says mainnet-beta and not devnet. */
    @Test
    fun theSignRequestStatesTheClusterOfTheNetworkOfTheSend() {
        val mainnet = SolanaSend.plan(SOLANA_MAINNET, account, recipient, lamports, neededBalance, blockhash, requestId)

        val request = assertIs<SolSignRequest.Result.Ok>(SolSignRequest.decode(urOfParts(SolanaSend.requestParts(mainnet)))).request

        assertEquals(SolCluster.MAINNET_BETA, request.cluster)
    }

    @Test
    fun aSignatureFromLensOverThePlanIsAssembledIntoTheSignedTransaction() {
        val plan = plan()
        val signature = Ed25519Signer.sign(privateKeyOf(accountIndex), plan.message)

        val signed = SolanaSend.signed(plan, answerOf(requestId, signature))

        assertEquals(Ed25519Signer.SIGNATURE_BYTES, signature.size)
        assertEquals(Base58.encode(signature), signed.signature)
        assertContentEquals(SolanaTransaction.signed(listOf(signature), plan.message), signed.raw)
    }

    @Test
    fun aSignatureForAnotherRequestIsRefused() {
        val plan = plan()
        val signature = Ed25519Signer.sign(privateKeyOf(accountIndex), plan.message)
        val otherRequest = ByteArray(16) { 0x33 }

        val refusal = assertFailsWith<IllegalArgumentException> { SolanaSend.signed(plan, answerOf(otherRequest, signature)) }

        assertTrue(refusal.message.orEmpty().contains("different request"))
    }

    @Test
    fun aSignatureByAnotherKeyIsRefusedBeforeAnythingIsSent() {
        val plan = plan()
        val byAccountZero = Ed25519Signer.sign(privateKeyOf(0), plan.message)

        val refusal = assertFailsWith<IllegalArgumentException> { SolanaSend.signed(plan, answerOf(requestId, byAccountZero)) }

        assertTrue(refusal.message.orEmpty().contains("does not match the account"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aSignatureOverOtherBytesIsRefusedEvenByTheRightKey() {
        val plan = plan()
        val overOtherBytes = Ed25519Signer.sign(privateKeyOf(accountIndex), plan.message + byteArrayOf(0))

        assertFailsWith<IllegalArgumentException> { SolanaSend.signed(plan, answerOf(requestId, overOtherBytes)) }
    }

    @Test
    fun aCodeThatIsNotASignatureIsRefused() {
        val request = urOfParts(SolanaSend.requestParts(plan()))

        val refusal = assertFailsWith<IllegalArgumentException> { SolanaSend.signed(plan(), request) }

        assertTrue(refusal.message.orEmpty().contains("not a signature from Lensly"))
    }

    @Test
    fun theSignedTransactionIsSentAsBase64AndItsSignatureIsConfirmed() = runBlocking {
        val plan = plan()
        val signed = SolanaSend.signed(plan, answerOf(requestId, Ed25519Signer.sign(privateKeyOf(accountIndex), plan.message)))
        server.enqueue(answer("\"${signed.signature}\""))

        val reported = SolanaSend.broadcast(scheduler(), SOLANA_DEVNET, signed)

        assertEquals(signed.signature, reported)
        val body = server.takeRequest().body?.utf8().orEmpty()
        assertTrue(body.contains("\"method\":\"sendTransaction\""))
        assertTrue(body.contains("[\"${Base64.getEncoder().encodeToString(signed.raw)}\",{\"encoding\":\"base64\"}]"))

        server.enqueue(answer("""{"context":{"slot":9},"value":[{"slot":9,"err":null,"confirmationStatus":"confirmed"}]}"""))
        assertEquals(SolanaOutcome.CONFIRMED, SolanaSend.confirm(scheduler(), SOLANA_DEVNET, signed.signature) { waits += it })
    }

    @Test
    fun aNetworkThatReportsAnotherSignatureIsNotTrusted() {
        val plan = plan()
        val signed = SolanaSend.signed(plan, answerOf(requestId, Ed25519Signer.sign(privateKeyOf(accountIndex), plan.message)))
        server.enqueue(answer("\"${"2".repeat(88)}\""))

        runBlocking {
            assertFailsWith<IllegalArgumentException> { SolanaSend.broadcast(scheduler(), SOLANA_DEVNET, signed) }
        }
    }

    @Test
    fun anExpiredBlockhashIsReportedWithTheWordsForTheOwner() = runBlocking {
        val plan = plan()
        val signed = SolanaSend.signed(plan, answerOf(requestId, Ed25519Signer.sign(privateKeyOf(accountIndex), plan.message)))
        server.enqueue(
            MockResponse.Builder().body(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32002,"message":"Transaction simulation failed: Blockhash not found","data":{}}}""",
            ).build(),
        )

        val failure = assertFailsWith<ProviderError.Fatal> { SolanaSend.broadcast(scheduler(), SOLANA_DEVNET, signed) }

        assertEquals(JsonRpc.BLOCKHASH_REFUSED_MESSAGE, failure.message)
    }

    @Test
    fun aSetupRefusedForAnAgedOutBlockhashSaysToStartItAgainAndOffersNoRetry() {
        val failure = solanaFailure(ProviderError.Fatal(JsonRpc.BLOCKHASH_REFUSED_MESSAGE), setup = true)

        assertEquals(SETUP_EXPIRED, failure.message)
        assertFalse(failure.retryable)
    }

    @Test
    fun aSendRefusedForAMovedNonceSaysTheNonceMovedAndNeverClaimsAge() {
        val failure = solanaFailure(ProviderError.Fatal(JsonRpc.BLOCKHASH_REFUSED_MESSAGE), setup = false)

        assertEquals(NONCE_MOVED, failure.message)
        assertFalse(failure.retryable)
        assertFalse(failure.message.contains("too old"))
    }

    @Test
    fun aFailureThatIsNotARefusedBlockhashKeepsItsWordsAndItsRetry() {
        val failure = solanaFailure(IllegalArgumentException("Enter an amount."), setup = false)

        assertEquals("Enter an amount.", failure.message)
        assertTrue(failure.retryable)
    }

    @Test
    fun theNoncePathIsTheSetupWhileNoNonceAccountExists() {
        assertEquals(SolanaNoncePath.Setup, SolanaSend.noncePath(null, sender))
    }

    @Test
    fun theStoredNonceOfAnInitializedNonceAccountOfThisAccountIsTheDurablePath() {
        val stored = ByteArray(32) { (it * 3 + 1).toByte() }
        val state = SolanaAccountState(owner = Base58.encode(ByteArray(32)), data = nonceAccount(authority = sender, nonce = stored))

        val path = assertIs<SolanaNoncePath.Durable>(SolanaSend.noncePath(state, sender))

        assertContentEquals(stored, path.nonceValue)
    }

    @Test
    fun aNonceAccountOwnedByAnotherProgramOrAuthorisedByAnotherKeyIsRefused() {
        val stored = ByteArray(32) { 7 }
        val other = SolanaAccount.publicKey(seed, 0)
        val byOtherOwner = SolanaAccountState(owner = Base58.encode(other), data = nonceAccount(authority = sender, nonce = stored))
        val byOtherAuthority = SolanaAccountState(owner = Base58.encode(ByteArray(32)), data = nonceAccount(authority = other, nonce = stored))

        assertFailsWith<IllegalArgumentException> { SolanaSend.noncePath(byOtherOwner, sender) }
        assertFailsWith<IllegalArgumentException> { SolanaSend.noncePath(byOtherAuthority, sender) }
    }

    /** An initialized nonce account's 80 bytes: version 1, state 1, the authority, the nonce value, then a fee of 5000 lamports per signature. */
    private fun nonceAccount(authority: ByteArray, nonce: ByteArray): ByteArray =
        ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(1).put(authority).put(nonce).putLong(5_000L).array()

    @Test
    fun confirmationStopsAtAFailedStatusAndWaitsBetweenPolls() = runBlocking {
        server.enqueue(answer("""{"context":{"slot":1},"value":[null]}"""))
        server.enqueue(
            answer(
                """{"context":{"slot":2},"value":[{"slot":2,"err":{"InstructionError":[0,{"Custom":1}]},"confirmationStatus":"processed"}]}""",
            ),
        )

        assertEquals(SolanaOutcome.FAILED, SolanaSend.confirm(scheduler(), SOLANA_DEVNET, SIGNATURE) { waits += it })
        assertEquals(listOf(2_000L), waits)
    }

    @Test
    fun confirmationGivesUpAfterTheLastPollWhenNoStatusComesIn() = runBlocking {
        repeat(30) { server.enqueue(answer("""{"context":{"slot":1},"value":[null]}""")) }

        assertEquals(SolanaOutcome.PENDING, SolanaSend.confirm(scheduler(), SOLANA_DEVNET, SIGNATURE) { waits += it })
        assertEquals(29, waits.size)
        assertTrue(waits.all { it == 2_000L })
    }

    @Test
    fun theExplorerLinkNamesTheDevnetClusterAndNamesNoClusterOnMainnet() {
        assertEquals("https://explorer.solana.com/tx/$SIGNATURE?cluster=devnet", SolanaSend.explorerTxUrl(SOLANA_DEVNET, SIGNATURE))
        assertEquals("https://explorer.solana.com/tx/$SIGNATURE", SolanaSend.explorerTxUrl(SOLANA_MAINNET, SIGNATURE))
    }

    private companion object {
        /** The 24-word BIP39 test vector of 256 zero bits, the phrase of the debug pairing fixture. */
        val PHRASE = "abandon ".repeat(23) + "art"
        const val RECIPIENT = "7tark5iZaRrMfGKtKy1aqpGuRgoxbE6ec7Z5Qa4Jc5xr"
        const val BLOCKHASH = "3K2oo9odsrvknS7gMzJ4eLBnjd3UG14yPuJc8DiviJqY"
        val SIGNATURE = "B".repeat(88)
    }
}
