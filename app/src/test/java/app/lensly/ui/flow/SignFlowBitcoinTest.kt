package app.lensly.ui.flow

import app.airvault.chains.bitcoin.signer.BtcPsbt
import app.airvault.chains.bitcoin.signer.BtcRefusal
import app.airvault.chains.bitcoin.signer.InputVerificationPolicy
import app.airvault.chains.bitcoin.signer.UNVERIFIED_CHECK
import app.airvault.chains.bitcoin.bitcoinManifest
import app.airvault.core.model.Approval
import app.airvault.core.signer.AppRequest
import app.lensly.app.BITCOIN_FAMILY
import app.lensly.app.BitcoinAccountRecord
import app.lensly.app.BitcoinReviewed
import app.lensly.app.WalletApp
import app.lensly.app.sign.AppPrepared
import app.lensly.app.sign.AppReviewEntry
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.WalletView
import app.lensly.app.sign.bitcoinRecordFactsOf
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.parse.ScanReply
import app.lensly.core.parse.inProcessSandboxParser
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.sign.SAMPLE_SIGNER
import app.lensly.ui.sign.bitcoinReviewOf
import fr.acinq.bitcoin.psbt.Psbt
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val BTC_WALLET_ID = "wallet-bitcoin"

private val BTC_SEED by lazy { Mnemonic.toSeed(List(23) { "abandon" } + "art") }
private val BTC_ACCOUNT by lazy { BtcAccount.fromSeed(BTC_SEED, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT) }

/** A request that spends the first output of account 0 and pays a receive address and the change of the same account. */
private fun bitcoinRequest(withPreviousTransaction: Boolean = true): Psbt {
    val spend = BtcFixtures.Spend(BtcFixtures.funding(BTC_ACCOUNT, 0, 0, 100_000L), BTC_ACCOUNT, 0, 0)
    return BtcFixtures.psbt(
        listOf(spend),
        BtcFixtures.paying(BTC_ACCOUNT, paid = 30_000L, change = 60_000L),
        withPreviousTransaction = withPreviousTransaction,
    )
}

/**
 * The review the app's wallet store would hand the flow for [request], under the given input policy. The app's review carries the record facts
 * of the Bitcoin review, as the Review Record keeps them.
 */
private fun readyOf(request: AppRequest, policy: InputVerificationPolicy = InputVerificationPolicy.STRICT): AppPrepared.Ready {
    val psbt = BtcPsbt.parse(BtcPsbt.requestPsbt(request.body))
    val record = BitcoinAccountRecord(BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, 0, BTC_ACCOUNT.receiveAddress(0))
    val review = BtcFixtures.signerOf(BTC_ACCOUNT, BTC_SEED, policy).review(psbt)
    val reviewed = BitcoinReviewed(record, review, SAMPLE_SIGNER)
    val core = bitcoinReviewOf(review, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, SAMPLE_SIGNER.address)
    return AppPrepared.Ready(
        walletId = BTC_WALLET_ID,
        request = request,
        review = core,
        accountIndex = 0,
        signer = SAMPLE_SIGNER,
        addressChain = BITCOIN_FAMILY,
        counterparties = review.outputs.mapNotNull { it.address },
        addressNotes = emptyMap(),
        bitcoin = bitcoinRecordFactsOf(reviewed, core),
    )
}

/** Feeds the frames of one scanned code to [flow], stopping once the flow has left the scanning step. */
private fun scanAll(flow: SignFlow, frames: List<String>) = runBlocking {
    for (frame in frames) {
        flow.onScanned(frame)
        if (flow.state.value !is SignStep.Scanning) break
    }
}

/**
 * The Bitcoin request through the one sign flow, with the app's side in [BitcoinPorts]. A Bitcoin request is scanned and parsed by the Parser
 * Sandbox, reviewed by the app's `appPrepare`, approved, signed by its `appSign`, and recorded by its `recordApp`.
 */
class SignFlowBitcoinTest {

    @Test
    fun aBitcoinRequestReachesItsReviewThroughTheSandbox() {
        val psbt = bitcoinRequest()
        val ports = BitcoinPorts(prepared = { readyOf(it) })
        val flow = SignFlow(ports, BTC_WALLET_ID)

        scanAll(flow, BtcPsbt.encodeUrParts(psbt))

        assertIs<SignStep.AppReview>(flow.state.value)
        assertEquals(emptySet(), flow.ticked.value)
        assertContentEquals(Psbt.write(psbt).toByteArray(), BtcPsbt.requestPsbt(ports.preparedRequests.single().body))
    }

    @Test
    fun aBitcoinRequestTheRulesRefuseShowsTheReason() {
        val psbt = bitcoinRequest()
        val flow = SignFlow(BitcoinPorts(prepared = { AppPrepared.Refused(BtcRefusal.NetworkMismatch.reason) }), BTC_WALLET_ID)

        scanAll(flow, BtcPsbt.encodeUrParts(psbt))

        assertEquals(SignStep.Refused(BtcRefusal.NetworkMismatch.reason), flow.state.value)
    }

    /**
     * A Bitcoin request for a wallet without the Bitcoin App asks for its install, before any review. The refusal is recorded under
     * that wallet, and nothing is signed.
     */
    @Test
    fun aBitcoinRequestForAWalletWithoutBitcoinAsksForItsInstall() {
        val ports = BitcoinPorts(installed = WalletApp.entries.toSet() - WalletApp.BITCOIN)
        val flow = SignFlow(ports, BTC_WALLET_ID)

        scanAll(flow, BtcPsbt.encodeUrParts(bitcoinRequest()))

        assertEquals(SignStep.Refused("Install the Bitcoin App to Sign This. Nothing was signed."), flow.state.value)
        assertTrue(ports.preparedRequests.isEmpty(), "No review is made for an App the wallet does not have")
        assertEquals(listOf(BTC_WALLET_ID), ports.recorded.map { it.walletId })
        assertEquals(0, ports.signCalls)
    }

    @Test
    fun aBitcoinRequestScannedWithoutAWalletReachesTheReviewOfTheWalletItIsFor() {
        val psbt = bitcoinRequest()
        val view = WalletView(
            id = BTC_WALLET_ID,
            masterFingerprint = BTC_ACCOUNT.masterFingerprint.toInt(),
            accountCount = 1,
            autoNameBase = "Bitcoin",
            addressAt = { _, _ -> BTC_ACCOUNT.receiveAddress(0) },
            storedAccountPaths = listOf(
                BitcoinAccountRecord(BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, 0, BTC_ACCOUNT.receiveAddress(0)).storedPath,
            ),
        )
        val flow = SignFlow(BitcoinPorts(prepared = { readyOf(it) }, wallets = listOf(view)), walletId = null)

        scanAll(flow, BtcPsbt.encodeUrParts(psbt))

        assertEquals(BTC_WALLET_ID, assertIs<SignStep.AppReview>(flow.state.value).ready.walletId)
    }

    @Test
    fun aBitcoinRequestNoWalletIsForIsRefusedInPlainWordsBeforeAnyReview() {
        val ports = BitcoinPorts()
        val flow = SignFlow(ports, walletId = null)

        scanAll(flow, BtcPsbt.encodeUrParts(bitcoinRequest()))

        assertEquals(SignStep.Refused("No wallet in this vault can sign this request"), flow.state.value)
        assertEquals(0, ports.preparedRequests.size)
    }

    @Test
    fun aSignedBitcoinRequestIsRecordedAsSignedWithTheReviewItWasSignedUnder() {
        val psbt = bitcoinRequest()
        val frames = BtcPsbt.encodeUrParts(psbt)
        val ports = BitcoinPorts(prepared = { readyOf(it) }, signed = { _, _ -> frames }, passwordAccepted = true)
        val flow = SignFlow(ports, BTC_WALLET_ID)
        scanAll(flow, BtcPsbt.encodeUrParts(psbt))
        flow.approve()

        runBlocking { flow.confirm("the password".toCharArray()) }

        assertEquals(frames, assertIs<SignStep.Signature>(flow.state.value).parts)
        val entry = ports.recorded.single()
        assertEquals(BTC_WALLET_ID, entry.walletId)
        assertEquals(ReviewOutcome.SIGNED, entry.outcome)
        assertEquals(null, entry.refusal)
        assertContentEquals(Psbt.write(psbt).toByteArray(), BtcPsbt.requestPsbt(entry.request.body))
        assertEquals(30_000L, entry.reviewed?.bitcoin?.outputs?.filterNot { it.isOwnChange }?.sumOf { it.amountSats })
    }

    @Test
    fun aSignatureWhoseRecordCannotBeWrittenIsNotShown() {
        val psbt = bitcoinRequest()
        val ports = BitcoinPorts(
            prepared = { readyOf(it) },
            signed = { _, _ -> BtcPsbt.encodeUrParts(psbt) },
            passwordAccepted = true,
            recordFails = true,
        )
        val flow = SignFlow(ports, BTC_WALLET_ID)
        scanAll(flow, BtcPsbt.encodeUrParts(psbt))
        flow.approve()

        runBlocking { flow.confirm("the password".toCharArray()) }

        assertEquals(SignStep.Refused("The review could not be saved, so the signature is not shown."), flow.state.value)
    }

    @Test
    fun aBitcoinReviewLeftUnsignedIsRecordedAsCancelled() {
        val psbt = bitcoinRequest()
        val ports = BitcoinPorts(prepared = { readyOf(it) })
        val flow = SignFlow(ports, BTC_WALLET_ID)
        scanAll(flow, BtcPsbt.encodeUrParts(psbt))

        runBlocking { flow.leave() }

        val entry = ports.recorded.single()
        assertEquals(ReviewOutcome.CANCELLED, entry.outcome)
        assertEquals(null, entry.refusal)
        assertEquals(true, entry.reviewed != null)
    }

    @Test
    fun aBitcoinRequestTheRulesRefuseIsRecordedAsRefusedWithItsReason() {
        val psbt = bitcoinRequest()
        val ports = BitcoinPorts(prepared = { AppPrepared.Refused(BtcRefusal.NetworkMismatch.reason) })
        val flow = SignFlow(ports, BTC_WALLET_ID)
        scanAll(flow, BtcPsbt.encodeUrParts(psbt))

        val entry = ports.recorded.single()
        assertEquals(ReviewOutcome.REFUSED, entry.outcome)
        assertEquals(BtcRefusal.NetworkMismatch.reason, entry.refusal)
        assertEquals(null, entry.reviewed)
    }

    @Test
    fun aBitcoinSigningRefusalIsRecordedWithTheReviewItCameAfter() {
        val psbt = bitcoinRequest()
        val ports = BitcoinPorts(
            prepared = { readyOf(it) },
            signed = { _, _ -> throw IllegalArgumentException(BtcRefusal.ConfirmationRequired.reason) },
            passwordAccepted = true,
        )
        val flow = SignFlow(ports, BTC_WALLET_ID)
        scanAll(flow, BtcPsbt.encodeUrParts(psbt))
        flow.approve()

        runBlocking { flow.confirm("the password".toCharArray()) }

        val entry = ports.recorded.single()
        assertEquals(ReviewOutcome.REFUSED, entry.outcome)
        assertEquals(BtcRefusal.ConfirmationRequired.reason, entry.refusal)
        assertEquals(true, entry.reviewed != null)
    }

    @Test
    fun unverifiedAmountsWaitForTheOwnersConfirmationBeforeApproval() {
        val psbt = bitcoinRequest(withPreviousTransaction = false)
        val flow = SignFlow(
            BitcoinPorts(prepared = { readyOf(it, InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING) }),
            BTC_WALLET_ID,
        )
        scanAll(flow, BtcPsbt.encodeUrParts(psbt))

        flow.approve()
        assertIs<SignStep.AppReview>(flow.state.value)

        flow.tick(UNVERIFIED_CHECK, on = true)
        flow.tick(UNVERIFIED_CHECK, on = false)
        flow.approve()
        assertIs<SignStep.AppReview>(flow.state.value)

        flow.tick(UNVERIFIED_CHECK, on = true)
        flow.approve()
        assertTrue(UNVERIFIED_CHECK in assertIs<SignStep.AppConfirming>(flow.state.value).approval.acknowledged)
    }

    @Test
    fun aBitcoinRequestIsSignedOnlyAfterThePasswordAndItsFramesAreShown() {
        val psbt = bitcoinRequest()
        val frames = BtcPsbt.encodeUrParts(psbt)
        val ports = BitcoinPorts(prepared = { readyOf(it) }, signed = { _, _ -> frames })
        val flow = SignFlow(ports, BTC_WALLET_ID)
        scanAll(flow, BtcPsbt.encodeUrParts(psbt))
        flow.approve()

        runBlocking { flow.confirm("not the password".toCharArray()) }
        // A wrong password signs nothing: the approval waits again.
        assertEquals(0, ports.signCalls)
        assertEquals(false, assertIs<SignStep.AppConfirming>(flow.state.value).busy)

        ports.passwordAccepted = true
        runBlocking { flow.confirm("the password".toCharArray()) }
        assertEquals(1, ports.signCalls)
        assertEquals(frames, assertIs<SignStep.Signature>(flow.state.value).parts)
    }

    @Test
    fun aBitcoinSigningRefusalIsShownAndNothingIsShown() {
        val psbt = bitcoinRequest()
        val ports = BitcoinPorts(
            prepared = { readyOf(it) },
            signed = { _, _ -> throw IllegalArgumentException(BtcRefusal.ConfirmationRequired.reason) },
            passwordAccepted = true,
        )
        val flow = SignFlow(ports, BTC_WALLET_ID)
        scanAll(flow, BtcPsbt.encodeUrParts(psbt))
        flow.approve()

        runBlocking { flow.confirm("the password".toCharArray()) }

        assertEquals(SignStep.Refused(BtcRefusal.ConfirmationRequired.reason), flow.state.value)
    }
}

/**
 * The app's side of the flow for a Bitcoin request. The app's functions answer the Bitcoin requests; the EVM ports are never reached by a
 * Bitcoin request, so they refuse if they are.
 */
private class BitcoinPorts(
    private val prepared: (AppRequest) -> AppPrepared = { error("no review is expected") },
    private val signed: (AppPrepared.Ready, Approval) -> List<String> = { _, _ -> error("no signature is expected") },
    var passwordAccepted: Boolean = false,
    private val recordFails: Boolean = false,
    private val wallets: List<WalletView> = emptyList(),
    /** The Apps installed in every wallet of the fake vault. */
    private val installed: Set<WalletApp> = WalletApp.entries.toSet(),
) : SignFlowPorts {
    val preparedRequests = mutableListOf<AppRequest>()
    val recorded = mutableListOf<AppReviewEntry>()
    var signCalls = 0

    override suspend fun wallets(): List<WalletView> = wallets

    override suspend fun scan(parts: List<String>): ScanReply = inProcessSandboxParser.scan(parts)

    override suspend fun checkPassword(password: CharArray): PasswordCheck =
        if (passwordAccepted) PasswordCheck.Accepted else PasswordCheck.Rejected

    override suspend fun isInstalled(walletId: String, app: WalletApp): Boolean = app in installed

    override suspend fun appPrepare(walletId: String, request: AppRequest): AppPrepared {
        check(request.header.app == bitcoinManifest.id) { "only a Bitcoin request is expected in the Bitcoin tests" }
        preparedRequests += request
        return prepared(request)
    }

    override suspend fun appSign(ready: AppPrepared.Ready, approval: Approval): List<String> {
        signCalls++
        return signed(ready, approval)
    }

    override suspend fun recordApp(entry: AppReviewEntry) {
        if (recordFails) throw ReviewNotSaved()
        recorded += entry
    }
}
