package app.lensly.core.btc

import app.airvault.chains.bitcoin.signer.BitcoinSigner
import app.airvault.chains.bitcoin.signer.BtcPsbt
import app.airvault.chains.bitcoin.signer.BtcRefusal
import app.airvault.chains.bitcoin.signer.BtcRefusedException
import app.airvault.chains.bitcoin.signer.bitcoinManifest
import app.airvault.chains.bitcoin.signer.toReview
import app.airvault.core.model.Approval
import app.airvault.core.model.ChainId
import app.airvault.core.model.NetworkLabel
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.WalletKeyAccess
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import fr.acinq.bitcoin.psbt.Psbt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class BitcoinSignTest {
    private val seed = BitcoinVectors.seed()
    private val native = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
    private val testnet4 = ChainId("bip122:00000000da84f2bafbbc53dee25a72ae")
    private val mainnet = ChainId("bip122:000000000019d6689c085ae165831e93")
    private val psbt: Psbt = BtcFixtures.psbt(
        listOf(BtcFixtures.Spend(BtcFixtures.funding(native, 0, 0, 100_000L), native, 0, 0)),
        BtcFixtures.paying(native, paid = 30_000L, change = 60_000L),
    )
    /** The account the request names: m/84'/1'/0' with the wallet's master fingerprint. */
    private val nativeAccount = SignerRef(
        native.masterFingerprint.toInt(),
        listOf(PathStep(84, hardened = true), PathStep(1, hardened = true), PathStep(0, hardened = true)),
    )
    private val otherAccount = nativeAccount.copy(
        path = listOf(PathStep(84, hardened = true), PathStep(1, hardened = true), PathStep(1, hardened = true)),
    )

    private fun request(signers: List<SignerRef>): AppRequest = AppRequest(
        RequestHeader(bitcoinManifest.id, testnet4, RequestKind.TRANSACTION, signers, requestId = null),
        Psbt.write(psbt).toByteArray(),
    )

    /** The approval of the review of [psbt] on [chain]. Nothing in this request needs a tick, so the approval exists. */
    private fun approval(chain: ChainId = testnet4): Approval {
        val review = BtcFixtures.signerOf(native, seed).review(psbt).toReview(
            chain = chain,
            network = NetworkLabel(name = "Bitcoin Testnet4", testnet = true, detail = null),
            scriptType = "Native segwit",
        )
        return checkNotNull(Approval.of(review, emptySet()))
    }

    @Test
    fun anApprovedRequestAnswersWithTheSignedPsbtThatTheSignerMakes() {
        val response = BitcoinSigner.sign(request(listOf(nativeAccount)), approval(), WalletKeyAccess(seed))
        val expected = BtcFixtures.signerOf(native, seed).sign(psbt).psbt
        assertEquals(BtcPsbt.UR_TYPE, response.urType)
        assertEquals(BtcPsbt.encodeUr(expected), UREncoder.encode(UR(response.urType, response.cbor)))
    }

    @Test
    fun aRequestThatNamesNoAccountOrSeveralIsRefused() {
        val none = assertFailsWith<BtcRefusedException> {
            BitcoinSigner.sign(request(emptyList()), approval(), WalletKeyAccess(seed))
        }
        assertEquals(BtcRefusal.NothingToSign, none.refusal)
        val several = assertFailsWith<BtcRefusedException> {
            BitcoinSigner.sign(request(listOf(nativeAccount, otherAccount)), approval(), WalletKeyAccess(seed))
        }
        assertEquals(BtcRefusal.SeveralAccounts, several.refusal)
    }

    @Test
    fun anApprovalForAnotherChainIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            BitcoinSigner.sign(request(listOf(nativeAccount)), approval(mainnet), WalletKeyAccess(seed))
        }
    }
}
