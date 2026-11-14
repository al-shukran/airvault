package app.lensly.ui.flow

import app.lensly.app.BitcoinAccountRecord
import app.lensly.app.BitcoinReviewed
import app.lensly.app.sign.Prepared
import app.lensly.app.sign.ReviewEntry
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
import fr.acinq.bitcoin.psbt.Psbt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Test

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
    return BitcoinPrepared.Ready(BTC_WALLET_ID, psbt, BitcoinReviewed(record, BtcSigner(BTC_ACCOUNT, policy).review(psbt)))
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
    fun aBitcoinRequestScannedWithoutAWalletIsRefusedBeforeAnyReview() {
        val ports = BitcoinPorts()
        val flow = SignFlow(ports, walletId = null)

        scanAll(flow, BtcPsbt.encodeUrParts(bitcoinRequest()))

        assertIs<SignStep.Refused>(flow.state.value)
        assertEquals(0, ports.preparedBytes.size)
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
) : SignFlowPorts {
    val preparedBytes = mutableListOf<ByteArray>()
    var signCalls = 0

    override suspend fun wallets(): List<WalletView> = emptyList()

    override suspend fun scan(parts: List<String>): ScanReply = inProcessSandboxParser.scan(parts)

    override suspend fun prepare(walletId: String, parsed: ParsedRequest): Prepared = error("an EVM request is not expected")

    override suspend fun privateKey(walletId: String, accountIndex: Int): ByteArray = error("an EVM key is not expected")

    override suspend fun checkPassword(password: CharArray): PasswordCheck =
        if (passwordAccepted) PasswordCheck.Accepted else PasswordCheck.Rejected

    override suspend fun sign(ready: Prepared.Ready, privateKey: ByteArray): List<String> = error("an EVM request is not expected")

    override suspend fun record(entry: ReviewEntry): Unit = error("no Review Record is written for a Bitcoin request")

    override suspend fun bitcoinPrepare(walletId: String, psbt: ByteArray): BitcoinPrepared {
        preparedBytes += psbt
        return prepared(psbt)
    }

    override suspend fun bitcoinSign(ready: BitcoinPrepared.Ready, unverifiedConfirmed: Boolean): List<String> {
        signCalls++
        return signed(ready, unverifiedConfirmed)
    }
}
