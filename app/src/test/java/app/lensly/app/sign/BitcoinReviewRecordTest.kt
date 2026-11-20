package app.lensly.app.sign

import app.airvault.chains.bitcoin.signer.InputVerificationPolicy
import app.airvault.chains.bitcoin.signer.UNVERIFIED_CHECK
import app.airvault.chains.bitcoin.bitcoinManifest
import app.airvault.core.model.checkLabels
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.lensly.app.BITCOIN_FAMILY
import app.lensly.app.BitcoinAccountRecord
import app.lensly.app.BitcoinReviewed
import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.sign.SAMPLE_SIGNER
import app.lensly.ui.sign.bitcoinReviewOf
import kotlin.test.assertEquals
import org.junit.Test

/** A Bitcoin record keeps the check the owner ticked before a signature, and no ticks on a request that was not signed. */
class BitcoinReviewRecordTest {
    private val seed = Mnemonic.toSeed(List(23) { "abandon" } + "art")
    private val account = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
    private val request = AppRequest(
        RequestHeader(bitcoinManifest.id, BITCOIN_FAMILY, RequestKind.TRANSACTION, signers = emptyList(), requestId = null),
        ByteArray(0),
    )

    /**
     * A segwit spend with only a witness UTXO, so its input amount is unverified. Under the warning policy the review asks the owner to tick
     * the check before it can be signed.
     */
    private fun reviewed(): AppPrepared.Ready {
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val psbt = BtcFixtures.psbt(
            listOf(spend),
            BtcFixtures.paying(account, paid = 30_000L, change = 60_000L),
            withPreviousTransaction = false,
        )
        val signer = BtcFixtures.signerOf(account, seed, InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING)
        val review = signer.review(psbt)
        val record = BitcoinAccountRecord(BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT, 0, account.receiveAddress(0))
        val core = bitcoinReviewOf(review, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
        return AppPrepared.Ready(
            walletId = "wallet",
            request = request,
            review = core,
            accountIndex = 0,
            signer = SAMPLE_SIGNER,
            addressChain = BITCOIN_FAMILY,
            counterparties = review.outputs.mapNotNull { it.address },
            addressNotes = emptyMap(),
            bitcoin = bitcoinRecordFactsOf(BitcoinReviewed(record, review, SAMPLE_SIGNER), core),
        )
    }

    @Test
    fun aSignedRecordKeepsTheUnverifiedAmountsCheckTheOwnerTicked() {
        val ready = reviewed()
        val entry = AppReviewEntry("wallet", ReviewOutcome.SIGNED, request, ready, acknowledged = ready.review.checkLabels(), refusal = null)

        assertEquals(listOf(UNVERIFIED_CHECK), bitcoinReviewRecordOf(entry, atMs = 1L).acknowledged)
    }

    @Test
    fun aRefusedRecordKeepsNoTicks() {
        val entry = AppReviewEntry(
            "wallet",
            ReviewOutcome.REFUSED,
            request,
            reviewed(),
            acknowledged = emptyList(),
            refusal = "Key does not match account",
        )

        assertEquals(emptyList(), bitcoinReviewRecordOf(entry, atMs = 1L).acknowledged)
    }
}
