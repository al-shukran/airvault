package app.lensly.core.btc

import app.airvault.chains.bitcoin.signer.BitcoinSigner
import app.airvault.chains.bitcoin.signer.BtcPsbt
import app.airvault.chains.bitcoin.signer.InputVerificationPolicy
import app.airvault.chains.bitcoin.signer.bitcoinManifest
import app.airvault.chains.bitcoin.signer.toReview
import app.airvault.core.model.Approval
import app.airvault.core.model.NetworkLabel
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.WalletKeyAccess
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.utils.Either
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The signatures BtcSigner attaches are the signatures bitcoin-kmp's Psbt.sign attaches, byte for byte. The oracle is Psbt.sign with the
 * account's own private key, which is the path the signer took before it moved to KeyAccess.
 */
class BtcSignerPsbtIdentityTest {
    private val seed = BitcoinVectors.seed()
    private val testnet4 = BtcNetwork.TESTNET4

    @Test
    fun everyScriptTypeAndKindOfInputSignsTheBytesPsbtSignWrites() {
        for (type in ScriptType.entries) {
            val account = BtcAccount.fromSeed(seed, testnet4, type)
            for (sighash in listOf(null, SigHash.SIGHASH_ALL)) {
                for (withPrevious in listOf(true, false)) {
                    // A legacy input always carries its previous transaction, so the witness-only variant is the same request.
                    if (type == ScriptType.LEGACY && !withPrevious) continue
                    val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
                    val psbt = BtcFixtures.psbt(
                        listOf(spend),
                        BtcFixtures.paying(account, paid = 30_000L, change = 60_000L),
                        sighash = sighash,
                        withPreviousTransaction = withPrevious,
                    )
                    // A witness UTXO alone verifies nothing, so only the weaker input policy accepts it, and then the amount is confirmed.
                    val policy = if (withPrevious) {
                        InputVerificationPolicy.STRICT
                    } else {
                        InputVerificationPolicy.SEGWIT_WITNESS_UTXO_WITH_WARNING
                    }

                    val signed = BtcFixtures.signerOf(account, seed, policy).sign(psbt, unverifiedAmountsConfirmed = true).psbt

                    val label = "${type.name} sighash=$sighash previousTransaction=$withPrevious"
                    assertFalse(Psbt.write(signed).toByteArray().contentEquals(Psbt.write(psbt).toByteArray()), "$label: nothing was signed")
                    val expected = Psbt.write(oracle(psbt, listOf(0 to spend))).toByteArray()
                    assertContentEquals(expected, Psbt.write(signed).toByteArray(), label)
                }
            }
        }
    }

    @Test
    fun ownedInputsOfTwoIndexesAndAForeignInputSignAsPsbtSignDoes() {
        val account = BtcAccount.fromSeed(seed, testnet4, ScriptType.NATIVE_SEGWIT)
        val foreign = BtcAccount.fromSeed(ByteArray(16) { 9 }, testnet4, ScriptType.NATIVE_SEGWIT)
        val spends = listOf(
            BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 40_000L, salt = 1), account, 0, 0),
            BtcFixtures.Spend(BtcFixtures.funding(foreign, 0, 0, 30_000L, salt = 2), foreign, 0, 0),
            BtcFixtures.Spend(BtcFixtures.funding(account, 1, 2, 70_000L, salt = 3), account, 1, 2),
        )
        // Inputs total 140,000 sats and outputs total 135,000 sats.
        val psbt = BtcFixtures.psbt(spends, BtcFixtures.paying(account, paid = 100_000L, change = 35_000L))

        val signed = BtcFixtures.signerOf(account, seed).sign(psbt).psbt

        assertFalse(Psbt.write(signed).toByteArray().contentEquals(Psbt.write(psbt).toByteArray()), "nothing was signed")
        assertContentEquals(
            Psbt.write(oracle(psbt, listOf(0 to spends[0], 2 to spends[2]))).toByteArray(),
            Psbt.write(signed).toByteArray(),
        )
        assertTrue(signed.inputs[1].partialSigs.isEmpty(), "the foreign input must stay unsigned")
    }

    /**
     * The one sign flow answers with the bytes Psbt.sign writes. [BitcoinSigner] signs the request the parser names, under the approval of the review,
     * and its response is the PSBT that the oracle signs, for every Script Type.
     */
    @Test
    fun theOneSignFlowAnswersWithTheBytesPsbtSignWrites() {
        for (type in ScriptType.entries) {
            val account = BtcAccount.fromSeed(seed, testnet4, type)
            val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
            val psbt = BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, paid = 30_000L, change = 60_000L))
            val review = BtcFixtures.signerOf(account, seed).review(psbt).toReview(
                chain = bitcoinManifest.networks.single { it.testnet }.chainId,
                network = NetworkLabel(name = "Bitcoin Testnet4", testnet = true, detail = null),
                scriptType = type.name,
            )
            val signer = SignerRef(
                account.masterFingerprint.toInt(),
                listOf(PathStep(type.purpose, hardened = true), PathStep(1, hardened = true), PathStep(0, hardened = true)),
            )
            val request = AppRequest(
                RequestHeader(bitcoinManifest.id, review.chain, RequestKind.TRANSACTION, listOf(signer), requestId = null),
                Psbt.write(psbt).toByteArray(),
            )

            val response = BitcoinSigner().sign(request, checkNotNull(Approval.of(review, emptySet())), WalletKeyAccess(seed))
            val signed = BtcPsbt.fromUr(UR(response.urType, response.cbor))

            assertContentEquals(
                Psbt.write(oracle(psbt, listOf(0 to spend))).toByteArray(),
                Psbt.write(signed).toByteArray(),
                type.name,
            )
        }
    }

    /**
     * The PSBT [psbt] with each of [owned] (an input index and its spend) signed by Psbt.sign with the key of the spend's address, in order.
     */
    private fun oracle(psbt: Psbt, owned: List<Pair<Int, BtcFixtures.Spend>>): Psbt {
        var signed = psbt
        for ((index, spend) in owned) {
            signed = when (val result = signed.sign(spend.account.privateKey(spend.branch, spend.index), index)) {
                is Either.Left -> error("Psbt.sign refused input $index: ${result.value}")
                is Either.Right -> result.value.psbt
            }
        }
        return signed
    }
}
