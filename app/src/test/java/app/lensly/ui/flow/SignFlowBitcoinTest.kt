package app.lensly.ui.flow

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.solana.signer.SolSignRequest
import app.lensly.app.BitcoinAccountRecord
import app.lensly.app.BitcoinReviewed
import app.lensly.app.sign.AlgorandReviewEntry
import app.lensly.app.sign.BitcoinReviewEntry
import app.lensly.app.sign.Prepared
import app.lensly.app.sign.ReviewEntry
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.SolanaReviewEntry
import app.lensly.app.sign.WalletView
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcPsbt
import app.lensly.core.btc.BtcRefusal
import app.lensly.core.btc.BtcRefusedException
import app.lensly.core.btc.BtcSigner
import app.lensly.core.btc.InputVerificationPolicy
import app.lensly.core.btc.ScriptType
import app.lensly.core.parse.ParsedRequest
import app.lensly.core.parse.ScanReply
import app.lensly.core.parse.inProcessSandboxParser
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.sign.SAMPLE_SIGNER
import fr.acinq.bitcoin.psbt.Psbt
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

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

/** The review the app's wallet store would hand the flow for [psbt], under the given input policy. */
private fun readyOf(psbt: Psbt, policy: InputVerificationPolicy = InputVerificationPolicy.STRICT): BitcoinPrepared.Ready {
    val record = BitcoinAccountRecord(BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, 0, BTC_ACCOUNT.receiveAddress(0))
    return BitcoinPrepared.Ready(BTC_WALLET_ID, psbt, BitcoinReviewed(record, BtcSigner(BTC_ACCOUNT, policy).review(psbt), SAMPLE_SIGNER))
}

/** Feeds the frames of one scanned code to [flow], stopping once the flow has left the scanning step. */
private fun scanAll(flow: SignFlow, frames: List<String>) = runBlocking {
    for (frame in frames) {
        flow.onScanned(frame)
        if (flow.state.value !is SignStep.Scanning) break
    }
}

class SignFlowBitcoinTest {

    @Test
    fun aBitcoinRequestReachesItsReviewThroughTheSandbox() {
        val psbt = bitcoinRequest()
        val ports = BitcoinPorts(prepared = { readyOf(psbt) })
        val flow = SignFlow(ports, BTC_WALLET_ID)

        scanAll(flow, BtcPsbt.encodeUrParts(psbt))

        val review = assertIs<SignStep.BitcoinReview>(flow.state.value)
        assertEquals(false, review.confirmed)
        assertContentEquals(Psbt.write(psbt).toByteArray(), ports.preparedBytes.single())
    }

    @Test
    fun aBitcoinRequestTheRulesRefuseShowsTheReason() {
        val psbt = bitcoinRequest()
        val flow = SignFlow(BitcoinPorts(prepared = { BitcoinPrepared.Refused(BtcRefusal.NetworkMismatch.reason) }), BTC_WALLET_ID)

        scanAll(flow, BtcPsbt.encodeUrParts(psbt))

        assertEquals(SignStep.Refused(BtcRefusal.NetworkMismatch.reason), flow.state.value)
    }

    @Test
    fun aBitcoinRequestScannedWithoutAWalletReachesTheReviewOfTheWalletItIsFor() {
        val psbt = bitcoinRequest()
        val view = WalletView(
            id = BTC_WALLET_ID,
            masterFingerprint = BTC_ACCOUNT.masterFingerprint.toInt(),
            accountCount = 1,
            autoNameBase = "Bitcoin",
            addressAt = { BTC_ACCOUNT.receiveAddress(0) },
            bitcoinAccountPaths = listOf(BTC_ACCOUNT.path),
        )
        val ports = BitcoinPorts(prepared = { readyOf(psbt) }, wallets = listOf(view))
        val flow = SignFlow(ports, walletId = null)

        scanAll(flow, BtcPsbt.encodeUrParts(psbt))

        assertEquals(BTC_WALLET_ID, assertIs<SignStep.BitcoinReview>(flow.state.value).ready.walletId)
    }

    @Test
    fun aBitcoinRequestNoWalletIsForIsRefusedInPlainWordsBeforeAnyReview() {
        val ports = BitcoinPorts()
        val flow = SignFlow(ports, walletId = null)

        scanAll(flow, BtcPsbt.encodeUrParts(bitcoinRequest()))

        assertEquals(SignStep.Refused("No wallet in this vault can sign this request"), flow.state.value)
        assertEquals(0, ports.preparedBytes.size)
    }

    @Test
    fun aSignedBitcoinRequestIsRecordedAsSignedWithTheReviewItWasSignedUnder() {
        val psbt = bitcoinRequest()
        val frames = BtcPsbt.encodeUrParts(psbt)
        val ports = BitcoinPorts(prepared = { readyOf(psbt) }, signed = { _, _ -> frames }, passwordAccepted = true)
        val flow = SignFlow(ports, BTC_WALLET_ID)
        scanAll(flow, BtcPsbt.encodeUrParts(psbt))
        flow.approve()

        runBlocking { flow.confirm("the password".toCharArray()) }

        assertEquals(frames, assertIs<SignStep.Signature>(flow.state.value).parts)
        val entry = ports.recorded.single()
        assertEquals(BTC_WALLET_ID, entry.walletId)
        assertEquals(ReviewOutcome.SIGNED, entry.outcome)
        assertEquals(null, entry.refusal)
        assertContentEquals(Psbt.write(psbt).toByteArray(), entry.psbt)
        assertEquals(30_000L, entry.reviewed?.review?.outputs?.filterNot { it.isOwnChange }?.sumOf { it.amountSats })
    }

    @Test
    fun aSignatureWhoseRecordCannotBeWrittenIsNotShown() {
        val psbt = bitcoinRequest()
        val ports = BitcoinPorts(
            prepared = { readyOf(psbt) },
            signed = { _, _ -> BtcPsbt.encodeUrParts(psbt) },
            passwordAccepted = true,
            recordFails = true,
        )
        val flow = SignFlow(ports, BTC_WALLET_ID)
        scanAll(flow, BtcPsbt.encodeUrParts(psbt))
        flow.approve()

        runBlocking { flow.confirm("the password".toCharArray()) }

        assertIs<SignStep.Refused>(flow.state.value)
    }

    @Test
    fun aBitcoinReviewLeftUnsignedIsRecordedAsCancelled() {
        val psbt = bitcoinRequest()
        val ports = BitcoinPorts(prepared = { readyOf(psbt) })
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
        val ports = BitcoinPorts(prepared = { BitcoinPrepared.Refused(BtcRefusal.NetworkMismatch.reason) })
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
            prepared = { readyOf(psbt) },
            signed = { _, _ -> throw BtcRefusedException(BtcRefusal.ConfirmationRequired) },
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
            BitcoinPorts(prepared = { readyOf(psbt, InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING) }),
            BTC_WALLET_ID,
        )
        scanAll(flow, BtcPsbt.encodeUrParts(psbt))

        flow.approve()
        assertEquals(false, assertIs<SignStep.BitcoinReview>(flow.state.value).confirmed)

        flow.confirmUnverified(true)
        flow.approve()
        assertEquals(true, assertIs<SignStep.BitcoinConfirming>(flow.state.value).confirmed)
    }

    @Test
    fun aBitcoinRequestIsSignedOnlyAfterThePasswordAndItsFramesAreShown() {
        val psbt = bitcoinRequest()
        val frames = BtcPsbt.encodeUrParts(psbt)
        val ports = BitcoinPorts(prepared = { readyOf(psbt) }, signed = { _, _ -> frames })
        val flow = SignFlow(ports, BTC_WALLET_ID)
        scanAll(flow, BtcPsbt.encodeUrParts(psbt))
        flow.approve()

        runBlocking { flow.confirm("not the password".toCharArray()) }
        // A wrong password signs nothing: the approval waits again.
        assertEquals(0, ports.signCalls)
        assertEquals(false, assertIs<SignStep.BitcoinConfirming>(flow.state.value).busy)

        ports.passwordAccepted = true
        runBlocking { flow.confirm("the password".toCharArray()) }
        assertEquals(1, ports.signCalls)
        assertEquals(frames, assertIs<SignStep.Signature>(flow.state.value).parts)
    }

    @Test
    fun aBitcoinSigningRefusalIsShownAndNothingIsShown() {
        val psbt = bitcoinRequest()
        val ports = BitcoinPorts(
            prepared = { readyOf(psbt) },
            signed = { _, _ -> throw BtcRefusedException(BtcRefusal.ConfirmationRequired) },
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
 * The app's side of the flow for a Bitcoin request. The Bitcoin ports are answered by the functions given; the EVM ports are never
 * reached by a Bitcoin request, so they refuse if they are.
 */
private class BitcoinPorts(
    private val prepared: (ByteArray) -> BitcoinPrepared = { error("no review is expected") },
    private val signed: (BitcoinPrepared.Ready, Boolean) -> List<String> = { _, _ -> error("no signature is expected") },
    var passwordAccepted: Boolean = false,
    private val recordFails: Boolean = false,
    private val wallets: List<WalletView> = emptyList(),
) : SignFlowPorts {
    val preparedBytes = mutableListOf<ByteArray>()
    val recorded = mutableListOf<BitcoinReviewEntry>()
    var signCalls = 0

    override suspend fun wallets(): List<WalletView> = wallets

    override suspend fun scan(parts: List<String>): ScanReply = inProcessSandboxParser.scan(parts)

    override suspend fun prepare(walletId: String, parsed: ParsedRequest): Prepared = error("an EVM request is not expected")

    override suspend fun privateKey(walletId: String, accountIndex: Int): ByteArray = error("an EVM key is not expected")

    override suspend fun checkPassword(password: CharArray): PasswordCheck =
        if (passwordAccepted) PasswordCheck.Accepted else PasswordCheck.Rejected

    override suspend fun sign(ready: Prepared.Ready, privateKey: ByteArray): List<String> = error("an EVM request is not expected")

    override suspend fun record(entry: ReviewEntry): Unit = error("no EVM Review Record is written for a Bitcoin request")

    override suspend fun recordBitcoin(entry: BitcoinReviewEntry) {
        if (recordFails) throw ReviewNotSaved()
        recorded += entry
    }

    override suspend fun bitcoinPrepare(walletId: String, psbt: ByteArray): BitcoinPrepared {
        preparedBytes += psbt
        return prepared(psbt)
    }

    override suspend fun bitcoinSign(ready: BitcoinPrepared.Ready, unverifiedConfirmed: Boolean): List<String> {
        signCalls++
        return signed(ready, unverifiedConfirmed)
    }

    override suspend fun solanaPrepare(walletId: String, request: SolSignRequest): SolanaPrepared =
        error("a Solana request is not expected in the Bitcoin tests")

    override suspend fun solanaSign(ready: SolanaPrepared.Ready): List<String> =
        error("a Solana request is not expected in the Bitcoin tests")

    override suspend fun recordSolana(entry: SolanaReviewEntry): Unit = error("a Solana request is not expected in the Bitcoin tests")

    override suspend fun algorandPrepare(walletId: String, request: AlgoSignRequest): AlgorandPrepared =
        error("an Algorand request is not expected in the Bitcoin tests")

    override suspend fun algorandSign(ready: AlgorandPrepared.Ready): List<String> =
        error("an Algorand request is not expected in the Bitcoin tests")

    override suspend fun recordAlgorand(entry: AlgorandReviewEntry): Unit =
        error("an Algorand request is not expected in the Bitcoin tests")
}
