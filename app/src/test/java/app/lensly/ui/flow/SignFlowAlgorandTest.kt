package app.lensly.ui.flow

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.AlgoSignType
import app.airvault.chains.algorand.algorandManifest
import app.airvault.chains.algorand.signer.AlgorandSigner
import app.airvault.chains.algorand.signer.toReview
import app.airvault.chains.evm.signer.ParsedRequest
import app.airvault.core.model.Approval
import app.airvault.core.model.Coverage
import app.airvault.core.model.Curve
import app.airvault.core.model.Danger
import app.airvault.core.model.FieldPath
import app.airvault.core.model.Review
import app.airvault.core.model.checkLabels
import app.airvault.core.model.checked
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.Path
import app.airvault.core.signer.ScopedKeyAccess
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.WalletKeyAccess
import app.lensly.app.export.partsOf
import app.lensly.app.ALGORAND_FAMILY
import app.lensly.app.sign.AppPrepared
import app.lensly.app.sign.AppReviewEntry
import app.lensly.app.sign.BitcoinReviewEntry
import app.lensly.app.sign.Prepared
import app.lensly.app.sign.ReviewEntry
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.WalletView
import app.lensly.core.algorand.AlgorandAccount
import app.airvault.chains.algorand.signer.AlgorandRequestCheck
import app.lensly.core.algorand.LedgerEd25519
import app.lensly.core.parse.RequestParser
import app.lensly.core.parse.ScanReply
import app.lensly.core.parse.inProcessSandboxParser
import app.lensly.core.ur.AlgoSignature
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.UrScanner
import app.lensly.core.ur.framesOf
import app.lensly.core.wallet.Ed25519Signer
import app.lensly.ui.sign.SAMPLE_SIGNER
import com.sparrowwallet.hummingbird.UR
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.web3j.crypto.MnemonicUtils
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Algorand sign flow: a scanned algo-sign-request is reviewed, approved, checked with the App Password, and answered with an
 * algo-signature that carries the account's public key. The ports are a fake. The scan goes through the real Parser Sandbox parser.
 */
class SignFlowAlgorandTest {

    @Test
    fun anAlgorandRequestIsReviewedThenSignedAndAnsweredWithAVerifyingSignature() = runBlocking {
        val ports = AlgorandPorts()
        val flow = SignFlow(ports, walletId = WALLET)

        flow.onScanned(partOf(requestCbor(PAY_BASIC)))

        val review = assertIs<SignStep.AppReview>(flow.state.value)
        assertEquals(0, review.ready.accountIndex)
        flow.approve()
        flow.confirm("the password".toCharArray())

        val answer = algorandSignatureOf(assertIs<SignStep.Signature>(flow.state.value).parts)
        assertContentEquals(REQUEST_ID, answer.requestId)
        assertContentEquals(ALGORAND_KEYS[0], answer.publicKey)
        assertTrue(Ed25519Signer.verify(ALGORAND_KEYS[0], TX_TAG + SIGN_DATA, answer.signature))
        assertContentEquals(legacySignature(0), answer.signature)
        assertEquals(listOf(ReviewOutcome.SIGNED), ports.records.map { it.outcome })
        assertEquals(1, ports.signCalls)
    }

    @Test
    fun anAlgorandRequestScannedWithoutAWalletReachesTheReviewOfTheWalletItIsFor() = runBlocking {
        val view = WalletView(id = WALLET, masterFingerprint = FINGERPRINT, accountCount = 1, autoNameBase = "Algorand", addressAt = { "" })
        val flow = SignFlow(AlgorandPorts(wallets = listOf(view)), walletId = null)

        flow.onScanned(partOf(requestCbor(PAY_BASIC)))

        assertEquals(WALLET, assertIs<SignStep.AppReview>(flow.state.value).ready.walletId)
    }

    @Test
    fun anAlgorandRequestNoWalletIsForIsRefusedInPlainWordsBeforeAnyCheck() = runBlocking {
        val other = WalletView(id = "other", masterFingerprint = FINGERPRINT xor 1, accountCount = 1, autoNameBase = "Other", addressAt = { "" })
        val ports = AlgorandPorts(wallets = listOf(other))
        val flow = SignFlow(ports, walletId = null)

        flow.onScanned(partOf(requestCbor(PAY_BASIC)))

        assertEquals(SignStep.Refused("No wallet in this vault can sign this request"), flow.state.value)
        assertEquals(0, ports.prepareCalls)
        assertTrue(ports.records.isEmpty())
    }

    /** An account the wallet does not have is refused, and the refusal is recorded under the wallet with its reason. */
    @Test
    fun anAccountTheWalletDoesNotHaveIsRefusedAndRecordedWithItsReason() = runBlocking {
        val ports = AlgorandPorts()
        val flow = SignFlow(ports, walletId = WALLET)

        flow.onScanned(partOf(requestCbor(PAY_BASIC, account = 5)))

        assertEquals(SignStep.Refused(AlgorandRequestCheck.UNKNOWN_ACCOUNT), flow.state.value)
        assertEquals(listOf(ReviewOutcome.REFUSED), ports.records.map { it.outcome })
        assertEquals(AlgorandRequestCheck.UNKNOWN_ACCOUNT, ports.records.single().refusal)
        assertEquals(0, ports.signCalls)
    }

    @Test
    fun aMessageToSignIsRefusedAndRecorded() = runBlocking {
        val ports = AlgorandPorts()
        val flow = SignFlow(ports, walletId = WALLET)

        flow.onScanned(partOf(requestCbor(PAY_BASIC, signType = AlgoSignType.MESSAGE)))

        assertEquals(SignStep.Refused(AlgorandRequestCheck.NOT_A_TRANSACTION), flow.state.value)
        assertEquals(listOf(ReviewOutcome.REFUSED), ports.records.map { it.outcome })
        assertEquals(AlgorandRequestCheck.NOT_A_TRANSACTION, ports.records.single().refusal)
        assertEquals(WALLET, ports.records.single().walletId)
        assertEquals(0, ports.prepareCalls)
        assertEquals(0, ports.signCalls)
    }

    @Test
    fun aWrongPasswordSignsNothingAndStaysOnTheHoldToSign() = runBlocking {
        val ports = AlgorandPorts(password = PasswordCheck.Rejected)
        val flow = SignFlow(ports, walletId = WALLET)
        flow.onScanned(partOf(requestCbor(PAY_BASIC)))

        flow.approve()
        flow.confirm("wrong".toCharArray())

        assertEquals(false, assertIs<SignStep.AppConfirming>(flow.state.value).busy)
        assertEquals(0, ports.signCalls)
        assertTrue(ports.records.isEmpty())
    }

    @Test
    fun leavingAReviewedRequestRecordsItAsCancelled() = runBlocking {
        val ports = AlgorandPorts()
        val flow = SignFlow(ports, walletId = WALLET)
        flow.onScanned(partOf(requestCbor(PAY_BASIC)))

        flow.leave()

        assertEquals(SignStep.Closed, flow.state.value)
        assertEquals(listOf(ReviewOutcome.CANCELLED), ports.records.map { it.outcome })
        assertEquals(0, ports.signCalls)
    }

    /**
     * A close-to payment has a check the owner ticks. Ticked and approved, then left at the hold-to-sign, the review is recorded as cancelled
     * with no ticked check: nothing was signed, so no tick is stored.
     */
    @Test
    fun aCancelledReviewStoresNoTickedChecks() = runBlocking {
        val ports = AlgorandPorts()
        val flow = SignFlow(ports, walletId = WALLET)
        flow.onScanned(partOf(requestCbor(PAY_CLOSE)))
        val label = assertIs<SignStep.AppReview>(flow.state.value).ready.review.checkLabels().single()
        flow.tick(label, on = true)
        flow.approve()
        assertIs<SignStep.AppConfirming>(flow.state.value)

        flow.leave()

        assertEquals(listOf(ReviewOutcome.CANCELLED), ports.records.map { it.outcome })
        assertEquals(emptyList(), ports.records.single().acknowledged)
        assertEquals(0, ports.signCalls)
    }

    /** The same tick, signed: the record names the check the owner ticked before the signature. */
    @Test
    fun aSignedReviewStoresTheCheckItsOwnerTicked() = runBlocking {
        val ports = AlgorandPorts()
        val flow = SignFlow(ports, walletId = WALLET)
        flow.onScanned(partOf(requestCbor(PAY_CLOSE)))
        val label = assertIs<SignStep.AppReview>(flow.state.value).ready.review.checkLabels().single()
        flow.tick(label, on = true)
        flow.approve()

        flow.confirm("the password".toCharArray())

        assertEquals(listOf(ReviewOutcome.SIGNED), ports.records.map { it.outcome })
        assertEquals(listOf(label), ports.records.single().acknowledged)
    }

    /**
     * A close-to payment carries one DANGER with one check. Approve does nothing until the owner ticks the check. Unticking it again, on
     * the review after the password step, stops approve again.
     */
    @Test
    fun anUntickedDangerCannotBeApprovedUntilItIsTicked() = runBlocking {
        val ports = AlgorandPorts()
        val flow = SignFlow(ports, walletId = WALLET)
        flow.onScanned(partOf(requestCbor(PAY_CLOSE)))
        val review = assertIs<SignStep.AppReview>(flow.state.value).ready.review
        val label = review.checkLabels().single()
        assertEquals(Danger.DANGER, review.warnings.single().level)

        flow.approve()
        assertIs<SignStep.AppReview>(flow.state.value)

        flow.tick(label, on = true)
        flow.approve()
        assertIs<SignStep.AppConfirming>(flow.state.value)

        flow.back()
        flow.tick(label, on = false)
        flow.approve()
        assertIs<SignStep.AppReview>(flow.state.value)
        assertEquals(0, ports.signCalls)
    }

    /**
     * Back on the review ends the flow, and the ticks on it end with it. A tick made on one review does not outlive that review.
     */
    @Test
    fun backOnTheReviewClearsItsTicksAndEndsTheFlow() = runBlocking {
        val ports = AlgorandPorts()
        val flow = SignFlow(ports, walletId = WALLET)
        flow.onScanned(partOf(requestCbor(PAY_CLOSE)))
        val label = assertIs<SignStep.AppReview>(flow.state.value).ready.review.checkLabels().single()
        flow.tick(label, on = true)
        assertEquals(setOf(label), flow.ticked.value)

        flow.back()

        assertEquals(SignStep.Closed, flow.state.value)
        assertEquals(emptySet(), flow.ticked.value)
        assertEquals(listOf(ReviewOutcome.CANCELLED), ports.records.map { it.outcome })
        assertEquals(emptyList(), ports.records.single().acknowledged)
        assertEquals(0, ports.signCalls)
    }

    /**
     * On the password step the ticks are frozen. A tick or an untick changes nothing, and the signature records the ticks that were
     * approved, not the ones shown after.
     */
    @Test
    fun aTickOnThePasswordStepChangesNothingAndSignsTheApprovedTicks() = runBlocking {
        val ports = AlgorandPorts()
        val flow = SignFlow(ports, walletId = WALLET)
        flow.onScanned(partOf(requestCbor(PAY_CLOSE)))
        val label = assertIs<SignStep.AppReview>(flow.state.value).ready.review.checkLabels().single()
        flow.tick(label, on = true)
        flow.approve()
        assertIs<SignStep.AppConfirming>(flow.state.value)

        flow.tick(label, on = false)
        flow.tick("Another check", on = true)

        assertIs<SignStep.AppConfirming>(flow.state.value)
        assertEquals(setOf(label), flow.ticked.value)
        flow.confirm("the password".toCharArray())
        assertEquals(listOf(ReviewOutcome.SIGNED), ports.records.map { it.outcome })
        assertEquals(listOf(label), ports.records.single().acknowledged)
        assertEquals(1, ports.signCalls)
    }

    /**
     * A coverage BLOCK is a decoded field that no row shows. Approve refuses it at flow level, and ticking the close-to DANGER's check does
     * not lift it.
     */
    @Test
    fun aCoverageBlockRefusesApproveEvenWithItsCheckTicked() = runBlocking {
        val ports = AlgorandPorts(unshownField = true)
        val flow = SignFlow(ports, walletId = WALLET)
        flow.onScanned(partOf(requestCbor(PAY_CLOSE)))
        val review = assertIs<SignStep.AppReview>(flow.state.value).ready.review
        assertEquals(listOf("coverage"), review.warnings.filter { it.level == Danger.BLOCK }.map { it.code })

        flow.tick(review.checkLabels().single(), on = true)
        flow.approve()

        assertIs<SignStep.AppReview>(flow.state.value)
        assertEquals(0, ports.signCalls)
    }

    /**
     * A request whose signing asks for a key outside the Algorand scope signs nothing, and the refusal is recorded with the reason the owner
     * saw. The scoped key access refuses the path before any key is derived, as the wallet's sign does.
     */
    @Test
    fun aKeyOutsideTheAlgorandScopeSignsNothingAndIsRecordedWithTheReasonShown() = runBlocking {
        val ports = AlgorandPorts(outOfScope = true)
        val flow = SignFlow(ports, walletId = WALLET)
        flow.onScanned(partOf(requestCbor(PAY_BASIC)))
        flow.approve()

        flow.confirm("the password".toCharArray())

        val shown = assertIs<SignStep.Refused>(flow.state.value)
        assertEquals(listOf(ReviewOutcome.REFUSED), ports.records.map { it.outcome })
        assertEquals(shown.reason, ports.records.single().refusal)
        assertEquals(1, ports.signCalls)
    }

    /** The fake ports. The wallet holds the Algorand accounts of the 24-word phrase, and the App Password check is [password]. */
    private class AlgorandPorts(
        private val password: PasswordCheck = PasswordCheck.Accepted,
        private val parser: RequestParser = inProcessSandboxParser,
        private val wallets: List<WalletView> = emptyList(),
        private val outOfScope: Boolean = false,
        private val unshownField: Boolean = false,
    ) : SignFlowPorts {
        val records = mutableListOf<AppReviewEntry>()
        var prepareCalls = 0
        var signCalls = 0

        override suspend fun wallets(): List<WalletView> = wallets

        override suspend fun scan(parts: List<String>): ScanReply = parser.scan(parts)

        override suspend fun prepare(walletId: String, parsed: ParsedRequest): Prepared =
            error("an EVM request is not expected in the Algorand tests")

        override suspend fun privateKey(walletId: String, accountIndex: Int): ByteArray =
            error("an EVM key is not read in the Algorand tests")

        override suspend fun checkPassword(password: CharArray): PasswordCheck = this.password

        override suspend fun sign(ready: Prepared.Ready, privateKey: ByteArray): List<String> =
            error("an EVM request is not expected in the Algorand tests")

        override suspend fun record(entry: ReviewEntry): Unit = error("an EVM request is not expected in the Algorand tests")

        override suspend fun recordBitcoin(entry: BitcoinReviewEntry): Unit = error("a Bitcoin request is not expected here")

        override suspend fun bitcoinPrepare(walletId: String, psbt: ByteArray): BitcoinPrepared =
            error("a Bitcoin request is not expected here")

        override suspend fun bitcoinSign(ready: BitcoinPrepared.Ready, unverifiedConfirmed: Boolean): List<String> =
            error("a Bitcoin request is not expected here")

        override suspend fun appPrepare(walletId: String, request: AppRequest): AppPrepared {
            prepareCalls++
            val algo = when (val decoded = AlgoSignRequest.decode(request.body)) {
                is AlgoSignRequest.Result.Ok -> decoded.request
                is AlgoSignRequest.Result.Rejected -> return AppPrepared.Refused(decoded.reason)
            }
            if (algo.sourceFingerprint != FINGERPRINT) return AppPrepared.Refused("This request is for a different wallet")
            return when (val checked = AlgorandRequestCheck.check(algo, ALGORAND_KEYS)) {
                is AlgorandRequestCheck.Result.Ok -> AppPrepared.Ready(
                    walletId = walletId,
                    request = request,
                    review = checked.review.toReview().let { if (unshownField) it.withUnshownField() else it },
                    accountIndex = checked.review.accountIndex,
                    signer = SAMPLE_SIGNER,
                    addressChain = ALGORAND_FAMILY,
                    counterparties = emptyList(),
                    addressNotes = emptyMap(),
                )
                is AlgorandRequestCheck.Result.Refused -> AppPrepared.Refused(checked.reason)
            }
        }

        /** The review with one decoded field that no row shows. The coverage check then adds a BLOCK, as it does for a field the app misses. */
        private fun Review.withUnshownField(): Review =
            copy(coverage = Coverage(coverage.decoded + FieldPath("algorand.unshown"), coverage.excluded)).checked()

        override suspend fun appSign(ready: AppPrepared.Ready, approval: Approval): List<String> {
            signCalls++
            val keys = ScopedKeyAccess(algorandManifest.keys, WalletKeyAccess(SEED))
            if (outOfScope) keys.signEd25519(Curve.ED25519_BIP32, OTHER_COIN_PATH, ByteArray(0))
            val response = AlgorandSigner.sign(ready.request, approval, keys)
            return partsOf(framesOf(UR(response.urType, response.cbor), DEFAULT_MAX_FRAGMENT))
        }

        override suspend fun recordApp(entry: AppReviewEntry) {
            records += entry
        }
    }

    private companion object {
        const val WALLET = "algorand-wallet"

        /** A path of Bitcoin's coin type, which no Algorand key scope covers. */
        val OTHER_COIN_PATH = Path(
            listOf(
                PathStep(44, hardened = true),
                PathStep(0, hardened = true),
                PathStep(0, hardened = true),
                PathStep(0, hardened = false),
                PathStep(0, hardened = false),
            ),
        )

        const val FINGERPRINT = 0x12121212
        val REQUEST_ID = ByteArray(16) { 0x11 }
        val TX_TAG = "TX".toByteArray(Charsets.US_ASCII)

        /** The 24-word phrase of the Mnemonic A vectors, which account 0 of the Ledger Algorand app derives from. */
        val SEED: ByteArray = MnemonicUtils.generateSeed(List(23) { "abandon" }.plus("art").joinToString(" "), "")
        val ALGORAND_KEYS: List<ByteArray> = (0..1).map { AlgorandAccount.publicKey(SEED, it) }

        /** The `pay_basic` vector of the SDK: 1.5 ALGO from account 0 of the wallet to a stranger, on testnet-v1.0. */
        const val PAY_BASIC =
            "89a3616d74ce0016e360a3666565cd03e8a26676ce02faf080a367656eac746573746e65742d76312e30a26768c4204863b5" +
                "18a4b3c84ec810f22d4f1081cb0f71f059a7ac20dec62f7f70e5093a22a26c76ce02faf468a3726376c420c11dff7e914904" +
                "1e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd" +
                "1467c72e3c660fc8c2bd2c6538a474797065a3706179"

        /** The `pay_note_close` vector of the SDK: a payment from account 0 that closes the account to another address, on testnet-v1.0. */
        const val PAY_CLOSE =
            "8ba3616d74ce77359400a5636c6f7365c4208d3ed18cb82bd72eab9a1bc21a77eeb097b7fd68f973f706036d219a165d006da3" +
                "666565cd03e8a26676ce02faf080a367656eac746573746e65742d76312e30a26768c4204863b518a4b3c84ec810f22d4f1081" +
                "cb0f71f059a7ac20dec62f7f70e5093a22a26c76ce02faf468a46e6f7465c40e72656e7420666f72204d61726368a3726376c4" +
                "20c11dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da3736e64c4207491ae7345271b581e36e46a" +
                "ba59dd0179b9bd1467c72e3c660fc8c2bd2c6538a474797065a3706179"

        /** The signed bytes: the transaction exactly as the wallet sent it, which the signature covers after "TX". */
        val SIGN_DATA: ByteArray = hex(PAY_BASIC)

        /** A request for the transaction [hexTxn], on account [account] of the wallet's path, as a wallet sends it. */
        fun requestCbor(
            hexTxn: String,
            account: Long = 0L,
            signType: AlgoSignType = AlgoSignType.TRANSACTION,
        ): ByteArray {
            val path = listOf(44L to true, 283L to true, account to true, 0L to false, 0L to false)
            val components = path.flatMap { (index, hardened) -> listOf(Cbor.Uint(index), Cbor.Bool(hardened)) }
            val keypath = Cbor.Tagged(
                304,
                Cbor.Dict(listOf(Cbor.Uint(1L) to Cbor.Arr(components), Cbor.Uint(2L) to Cbor.Uint(FINGERPRINT.toLong()))),
            )
            return Cbor.encode(
                Cbor.Dict(
                    listOf(
                        Cbor.Uint(1L) to Cbor.Tagged(37, Cbor.Bytes(REQUEST_ID)),
                        Cbor.Uint(2L) to Cbor.Bytes(hex(hexTxn)),
                        Cbor.Uint(3L) to keypath,
                        Cbor.Uint(4L) to Cbor.Text(""),
                        Cbor.Uint(5L) to Cbor.Text("lumio"),
                        Cbor.Uint(6L) to Cbor.Uint(signType.wire.toLong()),
                    ),
                ),
            )
        }

        /** The one frame of a request that fits in one QR code. */
        fun partOf(cbor: ByteArray): String = framesOf(UR("algo-sign-request", cbor), maxFragment = 4096).first()

        /** The decoded algo-signature in a single `ur:algo-signature` frame. */
        fun algorandSignatureOf(parts: List<String>): AlgoSignature {
            val scanner = UrScanner()
            assertTrue(scanner.receive(parts.single()))
            val ur = assertNotNull(scanner.result())
            assertEquals("algo-signature", ur.type)
            return assertIs<AlgoSignature.Result.Ok>(AlgoSignature.decode(ur)).signature
        }

        /** The signature the Ledger key of account [index] made before the one sign flow, over "TX" and the signed bytes. */
        fun legacySignature(index: Int): ByteArray {
            val key = LedgerEd25519.algorandAccount(SEED, index)
            try {
                return Ed25519Signer.sign(key.kL, TX_TAG + SIGN_DATA)
            } finally {
                key.wipe()
            }
        }

        fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
