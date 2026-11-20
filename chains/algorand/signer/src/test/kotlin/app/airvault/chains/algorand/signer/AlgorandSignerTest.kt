package app.airvault.chains.algorand.signer

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.AlgoSignResponse
import app.airvault.chains.algorand.bytesToSign
import app.airvault.core.model.Approval
import app.airvault.core.model.Curve
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.EcdsaSignature
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.ParseOutcome
import app.airvault.core.signer.Path
import app.airvault.core.signer.PublicKey
import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PathStep
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Signing an approved Algorand request: the key must be the transaction's sender, or nothing is signed (spec 3.2). */
class AlgorandSignerTest {

    /**
     * The derived key at the request's path is not the sender. The review was made against the stored key, which is the sender, so the
     * approval is valid. The sign refuses before any signature, because the key that would sign is not the one the transaction spends from.
     */
    @Test
    fun aSenderThatIsNotTheKeyDerivedForItsPathIsRefusedAtSign() {
        val keys = FakeKeys(derived = OTHER_KEY)

        val refused = assertFailsWith<IllegalArgumentException> { AlgorandSigner.sign(request, approval, keys) }

        assertEquals(AlgorandRequestCheck.NOT_THIS_SENDER, refused.message)
        assertTrue(keys.signed.isEmpty(), "A refused request signs nothing")
    }

    /** The control: the key derived for the path is the sender, so the transaction is signed as "TX" followed by its bytes. */
    @Test
    fun aTransactionSignsWithTheKeyDerivedForItsSender() {
        val keys = FakeKeys(derived = SENDER)

        val response = AlgorandSigner.sign(request, approval, keys)

        assertEquals(AlgoSignResponse.UR_TYPE, response.urType)
        assertContentEquals(bytesToSign(TXN), keys.signed.single())
    }

    /**
     * A transaction that rekeys the sender is refused at sign, as its review refuses it. The approval is the one for [TXN], which no review
     * would give for a rekey, so this shows the signer checks the bytes it signs rather than trusting the approval alone.
     */
    @Test
    fun aRekeyIsRefusedAtSignEvenWhenTheApprovalIsForAnotherTransaction() {
        val keys = FakeKeys(derived = SENDER)
        val rekey = assertIs<ParseOutcome.Parsed>(
            AlgorandRequestParser.parse(AlgoSignRequest.UR_TYPE, requestCbor(REKEY_TXN), ParserLimits()),
        ).request

        val refused = assertFailsWith<IllegalArgumentException> { AlgorandSigner.sign(rekey, approval, keys) }

        assertEquals(AlgorandRequestCheck.REKEY_REFUSED, refused.message)
        assertTrue(keys.signed.isEmpty(), "A refused request signs nothing")
    }

    /** The request as the parser reads it, for the transaction [TXN] on account 0 of [PATH]. */
    private val request: AppRequest = assertIs<ParseOutcome.Parsed>(
        AlgorandRequestParser.parse(AlgoSignRequest.UR_TYPE, requestCbor(), ParserLimits()),
    ).request

    /** The approval of that request, reviewed against the stored key [SENDER] as the wallet holds it. */
    private val approval: Approval = run {
        val algo = assertIs<AlgoSignRequest.Result.Ok>(AlgoSignRequest.decode(requestCbor())).request
        val checked = assertIs<AlgorandRequestCheck.Result.Ok>(AlgorandRequestCheck.check(algo, listOf(SENDER)))
        assertNotNull(Approval.of(checked.review.toReview(), emptySet()))
    }

    /** Key access whose derived key for every path is [derived]. It records each message it signs, so a test sees whether a key was used. */
    private class FakeKeys(private val derived: ByteArray) : KeyAccess {
        val signed = mutableListOf<ByteArray>()

        override fun publicKey(curve: Curve, path: Path): PublicKey = PublicKey(curve, derived)

        override fun signEd25519(curve: Curve, path: Path, message: ByteArray): ByteArray {
            signed += message
            return ByteArray(SIGNATURE_BYTES)
        }

        override fun signEcdsaSecp256k1(path: Path, digest32: ByteArray): EcdsaSignature = error("not used by Algorand")

        override fun signSchnorrBip340(path: Path, digest32: ByteArray, tweak: ByteArray?): ByteArray = error("not used by Algorand")

        override fun <T> withPrivateKey(curve: Curve, path: Path, block: (ByteArray) -> T): T = error("not used by Algorand")
    }

    /** An algo-sign-request for [TXN] on the path [PATH] of [FINGERPRINT], with the codec's fields as a wallet sends them. */
    private fun requestCbor(txn: ByteArray = TXN): ByteArray {
        val components = PATH.flatMap { listOf(Cbor.Uint(it.index.toLong()), Cbor.Bool(it.hardened)) }
        val keypath = Cbor.Tagged(
            304,
            Cbor.Dict(listOf(Cbor.Uint(1L) to Cbor.Arr(components), Cbor.Uint(2L) to Cbor.Uint(FINGERPRINT.toLong()))),
        )
        return Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(1L) to Cbor.Tagged(37, Cbor.Bytes(REQUEST_ID)),
                    Cbor.Uint(2L) to Cbor.Bytes(txn),
                    Cbor.Uint(3L) to keypath,
                    Cbor.Uint(4L) to Cbor.Text(""),
                    Cbor.Uint(5L) to Cbor.Text("lumio"),
                    Cbor.Uint(6L) to Cbor.Uint(1L),
                ),
            ),
        )
    }

    private companion object {
        const val FINGERPRINT = 0x12121212
        const val SIGNATURE_BYTES = 64
        val REQUEST_ID = ByteArray(16) { 0x11 }
        val PATH = listOf(PathStep(44, true), PathStep(283, true), PathStep(0, true), PathStep(0, false), PathStep(0, false))

        /** The `pay_basic` vector of the SDK: a payment from [SENDER] on testnet-v1.0. */
        val TXN: ByteArray = hex(
            "89a3616d74ce0016e360a3666565cd03e8a26676ce02faf080a367656eac746573746e65742d76312e30a26768c4204863b5" +
                "18a4b3c84ec810f22d4f1081cb0f71f059a7ac20dec62f7f70e5093a22a26c76ce02faf468a3726376c420c11dff7e914904" +
                "1e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd" +
                "1467c72e3c660fc8c2bd2c6538a474797065a3706179",
        )

        /** The SDK's `pay_rekey` vector: a payment from [SENDER] that rekeys the sender to another address. */
        val REKEY_TXN: ByteArray = hex(
            "89a3666565cd03e8a26676ce02faf080a367656eac746573746e65742d76312e30a26768c4204863b518a4b3c84ec810f22d" +
            "4f1081cb0f71f059a7ac20dec62f7f70e5093a22a26c76ce02faf468a3726376c4207491ae7345271b581e36e46aba59dd01" +
            "79b9bd1467c72e3c660fc8c2bd2c6538a572656b6579c4208d3ed18cb82bd72eab9a1bc21a77eeb097b7fd68f973f706036d" +
            "219a165d006da3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538a474797065a3" +
            "706179",
        )

        /** The public key the vector names as its sender. */
        val SENDER: ByteArray = hex("7491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538")

        /** A key other than the sender, which a wrong derivation would give. */
        val OTHER_KEY = ByteArray(32) { 0x55 }

        fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
