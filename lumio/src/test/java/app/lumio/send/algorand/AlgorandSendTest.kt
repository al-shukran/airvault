package app.lumio.send.algorand

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.AlgoSignType
import app.airvault.chains.algorand.Payment
import app.airvault.chains.algorand.bytesToSign
import app.airvault.chains.algorand.signer.AlgoTxnDecoder
import app.airvault.chains.algorand.wallet.AlgorandWallet
import app.airvault.chains.algorand.AlgoAddress
import app.lensly.core.algorand.AlgoSignedTxn
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.ur.AlgoSignatureEncoder
import app.lensly.core.ur.UrScanner
import app.lumio.data.MonotonicClock
import app.lumio.data.provider.AlgorandSuggestedParams
import app.lumio.data.provider.AsaHolding
import app.lumio.data.provider.Http
import app.lumio.data.provider.ProviderCatalog
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.algorand.ALGORAND_MAINNET
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import app.lumio.data.provider.algorand.AlgorandAlgod
import app.lumio.data.sched.Scheduler
import com.sparrowwallet.hummingbird.UR
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Algorand send without a device: the amount and address rules, the fee and the minimum balance, the plan, the sign request that Lensly reads,
 * the signature check before anything is sent, and the broadcast against a local MockWebServer that answers as algod does. Nothing reaches a network.
 */
class AlgorandSendTest {
    private val server = MockWebServer()

    private val privateKey = Ed25519PrivateKeyParameters(ByteArray(32) { 7 }, 0)
    private val signer: ByteArray = privateKey.generatePublicKey().encoded
    private val recipient = ByteArray(32) { 0x42 }
    private val params = AlgorandSuggestedParams(
        genesisId = "testnet-v1.0",
        genesisHash = java.util.Base64.getDecoder().decode("SGO1GKSzyE7IEPItTxCByw9x8FmnrCDexi9/cOUJOiI="),
        lastRound = 61_000_000L,
        feePerByte = 0L,
        minFee = 1_000L,
    )
    private val funds = AlgorandFunds(balance = BigInteger.valueOf(2_000_000L), params = params)
    private val requestId = ByteArray(16) { 1 }

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun plan(amount: BigInteger = BigInteger.valueOf(1_000_000L), at: AlgorandFunds = funds): AlgorandSendPlan =
        AlgorandSend.plan(ALGORAND_TESTNET, at, fingerprint = 0x1234_5678L, accountIndex = 3, signer = signer, to = recipient, amount = amount)

    /** An account of [balance] microAlgos that has opted in to [assets] assets, with no units of any of them. */
    private fun withAssets(balance: Long, assets: Int): AlgorandFunds = AlgorandFunds(
        balance = BigInteger.valueOf(balance),
        params = params,
        holdings = List(assets) { AsaHolding(assetId = (it + 1).toULong(), amount = BigInteger.ZERO, frozen = false) },
    )

    private fun sign(message: ByteArray): ByteArray {
        val engine = Ed25519Signer()
        engine.init(true, privateKey)
        engine.update(message, 0, message.size)
        return engine.generateSignature()
    }

    /** The algo-signature UR that Lensly would show, as the camera reads it. */
    private fun signatureUr(id: ByteArray, signature: ByteArray, publicKey: ByteArray? = signer): UR {
        val scanner = UrScanner()
        assertTrue(scanner.receive(AlgoSignatureEncoder.encode(id, signature, publicKey).first()))
        return checkNotNull(scanner.result())
    }

    @Test
    fun aMicroAlgoAmountIsReadInAlgoWithUpToSixDecimals() {
        assertEquals(BigInteger.valueOf(1_500_000L), AlgorandSend.microAlgos("1.5"))
        assertEquals(BigInteger.ONE, AlgorandSend.microAlgos("0.000001"))
        assertEquals(BigInteger.valueOf(2_000_000L), AlgorandSend.microAlgos(" 2 "))
    }

    @Test
    fun anAmountWithMoreDecimalsOrNoPositiveValueIsRefused() {
        for (text in listOf("0.0000001", "0", "0.0", "-1", "1e3", "abc", "")) {
            assertFailsWith<IllegalArgumentException>(text) { AlgorandSend.microAlgos(text) }
        }
    }

    @Test
    fun theAmountFieldShowsTheDigitsWithoutTrailingZeros() {
        assertEquals("1.5", AlgorandSend.algoText(BigInteger.valueOf(1_500_000L)))
        assertEquals("2", AlgorandSend.algoText(BigInteger.valueOf(2_000_000L)))
        assertEquals("0.000001", AlgorandSend.algoText(BigInteger.ONE))
    }

    @Test
    fun theMostThatCanBeSentKeepsTheMinimumBalanceAndTheFee() {
        assertEquals(BigInteger.valueOf(899_000L), AlgorandSend.maxMicro(withAssets(1_000_000L, 0), BigInteger.valueOf(1_000L)))
        assertEquals(BigInteger.ZERO, AlgorandSend.maxMicro(withAssets(50_000L, 0), BigInteger.valueOf(1_000L)))
    }

    @Test
    fun theMostThatCanBeSentKeepsAnotherMinimumBalanceForEachOptedInAsset() {
        // Three assets keep 0.4 ALGO in the account, so 0.599 ALGO is the most it can send with a fee of 0.001 ALGO.
        assertEquals(BigInteger.valueOf(599_000L), AlgorandSend.maxMicro(withAssets(1_000_000L, 3), BigInteger.valueOf(1_000L)))
    }

    @Test
    fun aValidAddressIsARecipientAndAChangedCharacterIsNot() {
        val address = AlgoAddress.encode(recipient)
        assertEquals(null, AlgorandSend.recipientError(address))
        assertContentEquals(recipient, AlgorandSend.recipientKey(address))
        val changed = address.dropLast(1) + if (address.last() == 'A') 'B' else 'A'
        assertTrue(AlgorandSend.recipientError(changed) != null)
        assertTrue(AlgorandSend.recipientError("ABC") != null)
    }

    @Test
    fun theDerivationPathIsTheLedgerAlgorandPathOfTheAccount() {
        val path = AlgorandWallet.pathOf(3)
        assertEquals(listOf(44, 283, 3, 0, 0), path.map { it.index })
        assertEquals(listOf(true, true, true, false, false), path.map { it.hardened })
    }

    @Test
    fun thePlanPaysTheRecipientFromTheCurrentRoundForAWindowOfAThousandRounds() {
        val plan = plan()

        val txn = AlgoTxnDecoder.decode(plan.txnBytes) as Payment
        assertContentEquals(signer, txn.sender)
        assertContentEquals(recipient, txn.receiver)
        assertEquals(1_000_000UL, txn.amount)
        assertEquals(61_000_000UL, txn.firstValid)
        assertEquals(61_001_000UL, txn.lastValid)
        assertEquals("testnet-v1.0", txn.genesisId)
        assertEquals(BigInteger.valueOf(1_000L), plan.fee)
        assertEquals(AlgoAddress.encode(signer), plan.fromAddress)
        assertEquals(AlgoAddress.encode(recipient), plan.toAddress)
    }

    @Test
    fun theFeeIsTheMinimumFeeWhenTheFeePerByteGivesLess() {
        assertEquals(BigInteger.valueOf(1_000L), plan().fee)
    }

    @Test
    fun theFeeCoversTheSignedSizeAtTheFeePerByte() {
        val costly = AlgorandFunds(
            balance = BigInteger.valueOf(10_000_000L),
            params = params.copy(feePerByte = 10L),
        )

        val plan = plan(at = costly)

        val size = AlgoSignedTxn.assemble(plan.txnBytes, ByteArray(AlgoSignedTxn.SIGNATURE_BYTES)).size
        assertTrue(plan.fee >= BigInteger.valueOf(10L * size), "fee ${plan.fee} covers $size bytes at 10 per byte")
        assertEquals(plan.fee, AlgorandSend.estimateFee(costly, signer, recipient, BigInteger.valueOf(1_000_000L)))
    }

    @Test
    fun anAmountThatLeavesLessThanTheMinimumBalanceIsRefused() {
        // The account holds 2 ALGO and must keep 0.1 ALGO, so 1.899 ALGO is the most it can send with a fee of 0.001 ALGO.
        assertFailsWith<IllegalArgumentException> { plan(BigInteger.valueOf(1_900_000L)) }
        assertEquals(BigInteger.valueOf(1_899_000L), plan(BigInteger.valueOf(1_899_000L)).amount)
    }

    @Test
    fun anAmountThatLeavesLessThanTheMinimumBalanceForTheOptedInAssetsIsRefused() {
        // Three assets keep 0.4 ALGO, so an account with 0.2 ALGO more than the amount and fee is refused, and one with 0.4 ALGO more is not.
        assertFailsWith<IllegalArgumentException> { plan(at = withAssets(1_000_000L + 1_000L + 200_000L, 3)) }
        assertEquals(BigInteger.valueOf(1_000_000L), plan(at = withAssets(1_000_000L + 1_000L + 400_000L, 3)).amount)
    }

    @Test
    fun theSignRequestCarriesTheTransactionForLensly() {
        val plan = plan()
        val scanner = UrScanner()
        AlgorandSend.requestParts(plan, requestId).forEach { assertTrue(scanner.receive(it)) }

        val request = (AlgoSignRequest.decode(checkNotNull(scanner.result())) as AlgoSignRequest.Result.Ok).request

        assertContentEquals(plan.txnBytes, request.signData)
        assertContentEquals(requestId, request.requestId)
        assertEquals(AlgoSignType.TRANSACTION, request.signType)
        assertEquals(plan.fromAddress, request.address)
        assertEquals(AlgorandSend.ORIGIN, request.origin)
        assertEquals(0x1234_5678, request.sourceFingerprint)
        assertEquals(listOf(44, 283, 3, 0, 0), request.derivationPath.map { it.index })
        assertEquals(AlgorandWallet.pathOf(3), request.derivationPath)
    }

    @Test
    fun aSignatureOverTheTransactionIsAcceptedAndAssembled() {
        val plan = plan()
        val signature = sign(bytesToSign(plan.txnBytes))

        val signed = AlgorandSend.signed(plan, requestId, signatureUr(requestId, signature))

        assertContentEquals(AlgoSignedTxn.assemble(plan.txnBytes, signature), signed)
    }

    @Test
    fun aWrongSignatureIsRefusedBeforeItIsSent() {
        val plan = plan()
        val wrong = sign(bytesToSign(plan.txnBytes)).also { it[0] = (it[0].toInt() xor 0x40).toByte() }

        val error = assertFailsWith<IllegalArgumentException> { AlgorandSend.signed(plan, requestId, signatureUr(requestId, wrong)) }

        assertEquals("This signature does not match this transaction. Scan the signature again.", error.message)
    }

    @Test
    fun aSignatureOverTheTransactionWithoutTheTagIsRefused() {
        val plan = plan()

        val error = assertFailsWith<IllegalArgumentException> {
            AlgorandSend.signed(plan, requestId, signatureUr(requestId, sign(plan.txnBytes)))
        }

        assertEquals("This signature does not match this transaction. Scan the signature again.", error.message)
    }

    @Test
    fun aSignatureOfAnotherTransactionIsRefused() {
        val plan = plan()
        val other = plan(amount = BigInteger.valueOf(1_500_000L))
        val signature = sign(bytesToSign(other.txnBytes))

        assertFailsWith<IllegalArgumentException> { AlgorandSend.signed(plan, requestId, signatureUr(requestId, signature)) }
    }

    @Test
    fun aSignatureForAnotherRequestIsRefused() {
        val plan = plan()
        val signature = sign(bytesToSign(plan.txnBytes))

        val error = assertFailsWith<IllegalArgumentException> {
            AlgorandSend.signed(plan, ByteArray(16) { 9 }, signatureUr(requestId, signature))
        }

        assertEquals("This signature answers a different request. Scan the signature for this send.", error.message)
    }

    @Test
    fun aSignatureFromAnotherAccountIsRefused() {
        val plan = plan()
        val otherKey = Ed25519PrivateKeyParameters(ByteArray(32) { 9 }, 0).generatePublicKey().encoded
        val signature = sign(bytesToSign(plan.txnBytes))

        val error = assertFailsWith<IllegalArgumentException> {
            AlgorandSend.signed(plan, requestId, signatureUr(requestId, signature, publicKey = otherKey))
        }

        assertEquals("This signature is from another account.", error.message)
    }

    @Test
    fun aCodeOfAnotherTypeIsNotASignature() {
        val notASignature = UR("crypto-hdkey", byteArrayOf(0x01))

        val error = assertFailsWith<IllegalArgumentException> { AlgorandSend.signed(plan(), requestId, notASignature) }

        assertEquals("That code is not a signature from Lensly. Scan the signature that Lensly shows.", error.message)
    }

    @Test
    fun theBroadcastReturnsTheIdTheNetworkReportsForThePlanTransaction() = runBlocking {
        val plan = plan()
        val signed = AlgoSignedTxn.assemble(plan.txnBytes, ByteArray(64) { 1 })
        server.enqueue(MockResponse().setBody("""{"txId":"${AlgoSignedTxn.txid(plan.txnBytes)}"}"""))

        val txid = AlgorandSend.broadcast(scheduler(this), plan, signed)

        assertEquals(AlgoSignedTxn.txid(plan.txnBytes), txid)
        assertContentEquals(signed, server.takeRequest().body.readByteArray())
    }

    @Test
    fun theBroadcastIsRefusedWhenTheNetworkReportsAnotherTransaction() = runBlocking {
        val plan = plan()
        val signed = AlgoSignedTxn.assemble(plan.txnBytes, ByteArray(64) { 1 })
        server.enqueue(MockResponse().setBody("""{"txId":"${AlgoSignedTxn.txid(plan(BigInteger.TEN).txnBytes)}"}"""))

        val error = assertFailsWith<IllegalArgumentException> { AlgorandSend.broadcast(scheduler(this), plan, signed) }

        assertTrue(checkNotNull(error.message).contains("different transaction id"))
    }

    @Test
    fun theExplorerIsLoraOnTheNetworkOfThePlan() {
        assertEquals("https://lora.algokit.io/testnet/transaction/ABC", AlgorandSend.explorerUrl(ALGORAND_TESTNET, "ABC"))
        assertEquals("https://lora.algokit.io/mainnet/transaction/ABC", AlgorandSend.explorerUrl(ALGORAND_MAINNET, "ABC"))
    }

    @Test
    fun aNetworkFailureShowsTheWordsOfANetworkFailure() {
        assertEquals(
            "The Algorand network could not be read. Try again.",
            AlgorandSend.ownerWords(ProviderError.Transient(), "fallback"),
        )
        assertEquals("fallback", AlgorandSend.ownerWords(IllegalStateException(), "fallback"))
    }

    /**
     * Answers the local algod as the network answers at review: the balance of an account and the next round are read at each call, so a test
     * can move the network between the read a form takes when it opens and the read a review takes.
     */
    private fun algodAt(balance: () -> Long, round: () -> Long) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/v2/accounts/") == true -> MockResponse().setBody("""{"amount":${balance()},"assets":[]}""")
                request.path == "/v2/transactions/params" -> MockResponse().setBody(
                    """{"genesis-id":"testnet-v1.0","genesis-hash":"SGO1GKSzyE7IEPItTxCByw9x8FmnrCDexi9/cOUJOiI=","last-round":${round()},""" +
                        """"fee":0,"min-fee":1000}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test
    fun theReviewPlansFromTheRoundOfTheNetworkAtReviewNotTheRoundTheFormOpenedAt() = runBlocking {
        var round = 61_000_000L
        algodAt(balance = { 2_000_000L }, round = { round })
        AlgorandSend.funds(scheduler(this), ALGORAND_TESTNET, AlgorandAccount.addressOf(signer)) // the form opens at round 61,000,000
        round = 61_001_200L // the form sits open past the 1,000-round window

        val plan = reviewPlan(
            scheduler(this), ALGORAND_TESTNET, AlgorandAccount.addressOf(signer), AlgorandAssets.native, AlgoAddress.encode(recipient),
            "1", 0x1234_5678L, 3, signer,
        )

        val txn = AlgoTxnDecoder.decode(plan.txnBytes) as Payment
        assertEquals(61_001_200UL, txn.firstValid)
        assertEquals(61_002_200UL, txn.lastValid)
    }

    @Test
    fun theBalanceAtReviewDecidesTheSendNotTheBalanceTheFormOpenedWith() {
        runBlocking {
            var balance = 2_000_000L
            algodAt(balance = { balance }, round = { 61_000_000L })
            AlgorandSend.funds(scheduler(this), ALGORAND_TESTNET, AlgorandAccount.addressOf(signer)) // the form opens with 2 ALGO
            balance = 1_000_500L // the account is spent down since the form opened

            assertFailsWith<IllegalArgumentException> {
                reviewPlan(
                    scheduler(this), ALGORAND_TESTNET, AlgorandAccount.addressOf(signer), AlgorandAssets.native, AlgoAddress.encode(recipient),
                    "1", 0x1234_5678L, 3, signer,
                )
            }
        }
    }

    private fun scheduler(scope: CoroutineScope): Scheduler = Scheduler(
        providers = listOf(AlgorandAlgod(mapOf("testnet" to server.url("/").toString().trimEnd('/')))),
        configs = { ProviderCatalog.withDefaults(emptyMap()) },
        http = Http(OkHttpClient()),
        clock = MonotonicClock,
        random = Random(7),
        scope = scope,
    )
}
