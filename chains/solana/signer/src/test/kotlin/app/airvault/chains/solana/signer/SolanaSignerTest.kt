package app.airvault.chains.solana.signer

import app.airvault.chains.solana.SOLANA_DEVNET
import app.airvault.chains.solana.solanaManifest
import app.airvault.core.model.AddressKind
import app.airvault.core.model.AddressResult
import app.airvault.core.model.Approval
import app.airvault.core.model.Curve
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.EcdsaSignature
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.KeyScopeViolation
import app.airvault.core.signer.ParseOutcome
import app.airvault.core.signer.Path
import app.airvault.core.signer.PublicKey
import app.airvault.core.signer.ScopedKeyAccess
import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PathStep
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SolanaSignerTest {
    private val chain = solanaManifest.networks.first().chainId

    @Test
    fun theAddressCodecGivesTheBase58AddressOfAKey() {
        // The all-zero key is the System Program's address, which is 32 ones in base58.
        val address = "11111111111111111111111111111111"
        assertEquals(address, SolanaSigner.addresses.fromPublicKey(chain, ByteArray(32), AddressKind.DEFAULT))
        assertEquals(AddressResult.Valid(address, AddressKind.DEFAULT), SolanaSigner.addresses.parse(chain, address))
    }

    /** The approved transaction is signed with the key derived for its path: the sign data as it is, and the response echoes the request. */
    @Test
    fun aTransactionIsSignedWithTheKeyOfItsNamedAccountAndAnswersWithItsRequestId() {
        val keys = FakeKeys(derived = SIGNER)

        val response = SolanaSigner.sign(request(), approval(request()), keys)

        assertEquals(SolSignResponse.UR_TYPE, response.urType)
        assertContentEquals(MESSAGE, keys.signed.single())
        val fields = assertIs<Cbor.Dict>(Cbor.decode(response.cbor)).entries
        assertContentEquals(REQUEST_ID, ((fields[0].second as Cbor.Tagged).value as Cbor.Bytes).value)
        assertContentEquals(FakeKeys.SIGNATURE, (fields[1].second as Cbor.Bytes).value)
    }

    /** The request names the account's address, and the key derived for the path is another one: nothing is signed. */
    @Test
    fun aKeyThatIsNotTheNamedAddressSignsNothing() {
        val named = request(address = SIGNER)
        val keys = FakeKeys(derived = OTHER_KEY)

        val refused = assertFailsWith<IllegalArgumentException> { SolanaSigner.sign(named, approval(named), keys) }

        assertEquals(SolanaRequestCheck.DIFFERENT_ADDRESS, refused.message)
        assertTrue(keys.signed.isEmpty(), "A refused request signs nothing")
    }

    /** A transaction whose required signers do not include the derived key is refused at sign, as the review refuses it. */
    @Test
    fun aKeyThatDoesNotSignTheTransactionSignsNothing() {
        val keys = FakeKeys(derived = OTHER_KEY)

        val refused = assertFailsWith<IllegalArgumentException> { SolanaSigner.sign(request(), approval(request()), keys) }

        assertEquals(SolanaRequestCheck.NOT_A_SIGNER, refused.message)
        assertTrue(keys.signed.isEmpty())
    }

    @Test
    fun anApprovalForAnotherClusterIsRefused() {
        val devnet = request(cluster = "devnet")
        val keys = FakeKeys(derived = SIGNER)

        val refused = assertFailsWith<IllegalArgumentException> { SolanaSigner.sign(devnet, approval(request()), keys) }

        assertEquals("The approval is for another chain.", refused.message)
        assertEquals(SOLANA_DEVNET, devnet.header.chain)
        assertTrue(keys.signed.isEmpty())
    }

    @Test
    fun aRequestWithNoRequestIdIsRefused() {
        val unnamed = request(requestId = null)
        val keys = FakeKeys(derived = SIGNER)

        assertFailsWith<IllegalArgumentException> { SolanaSigner.sign(unnamed, approval(unnamed), keys) }

        assertTrue(keys.signed.isEmpty())
    }

    /** A key access that scopes no key to the Solana path refuses the call, and the signer lets the core's KeyScopeViolation through. */
    @Test
    fun aPathOutsideTheKeyScopesThrowsKeyScopeViolationBeforeAnySignature() {
        val keys = FakeKeys(derived = SIGNER)
        val scoped = ScopedKeyAccess(scopes = emptyList(), delegate = keys)

        assertFailsWith<KeyScopeViolation> { SolanaSigner.sign(request(), approval(request()), scoped) }

        assertTrue(keys.signed.isEmpty())
    }

    /** The request as the parser reads it: a legacy transaction for account 0 of [PATH], with the given [address] or [cluster] when set. */
    private fun request(requestId: ByteArray? = REQUEST_ID, address: ByteArray? = null, cluster: String? = null): AppRequest {
        val parsed = SolanaRequestParser.parse(SolSignRequest.UR_TYPE, requestCbor(requestId, address, cluster), ParserLimits())
        return assertIs<ParseOutcome.Parsed>(parsed).request
    }

    /** The approval of the request for the transaction [MESSAGE], reviewed against the account key [SIGNER] as the wallet holds it. */
    private fun approval(request: AppRequest): Approval {
        val sol = assertIs<SolSignRequest.Result.Ok>(SolSignRequest.decode(request.body)).request
        val checked = assertIs<SolanaRequestCheck.Result.Ok>(SolanaRequestCheck.check(sol, listOf(SIGNER)))
        return requireNotNull(Approval.of(checked.review.toReview(), emptySet()))
    }

    /** Key access whose derived key for every path is [derived]. It records each message it signs, so a test sees whether a key was used. */
    private class FakeKeys(private val derived: ByteArray) : KeyAccess {
        val signed = mutableListOf<ByteArray>()

        override fun publicKey(curve: Curve, path: Path): PublicKey = PublicKey(curve, derived)

        override fun signEd25519(curve: Curve, path: Path, message: ByteArray): ByteArray {
            signed += message
            return SIGNATURE
        }

        override fun signEcdsaSecp256k1(path: Path, digest32: ByteArray): EcdsaSignature = error("not used by Solana")

        override fun signSchnorrBip340(path: Path, digest32: ByteArray, tweak: ByteArray?): ByteArray = error("not used by Solana")

        override fun <T> withPrivateKey(curve: Curve, path: Path, block: (ByteArray) -> T): T = error("not used by Solana")

        companion object {
            /** The signature the fake gives for any message: a fixed 64 bytes, so the response can be checked against it. */
            val SIGNATURE = ByteArray(64) { 0x5a }
        }
    }

    /** A sol-sign-request on account 0 of 44'/501'/0', for the legacy transaction [MESSAGE] that [SIGNER] signs. */
    private fun requestCbor(requestId: ByteArray?, address: ByteArray?, cluster: String?): ByteArray {
        val components = PATH.flatMap { listOf(Cbor.Uint(it.index.toLong()), Cbor.Bool(it.hardened)) }
        val keypath = Cbor.Tagged(304, Cbor.Dict(listOf(Cbor.Uint(1L) to Cbor.Arr(components), Cbor.Uint(2L) to Cbor.Uint(FINGERPRINT))))
        val fields = mutableListOf<Pair<Cbor, Cbor>>(
            Cbor.Uint(2L) to Cbor.Bytes(MESSAGE),
            Cbor.Uint(3L) to keypath,
            Cbor.Uint(5L) to Cbor.Text("solflare"),
            Cbor.Uint(6L) to Cbor.Uint(1L),
        )
        if (requestId != null) fields += Cbor.Uint(1L) to Cbor.Tagged(37, Cbor.Bytes(requestId))
        if (address != null) fields += Cbor.Uint(4L) to Cbor.Bytes(address)
        if (cluster != null) fields += Cbor.Uint(7L) to Cbor.Text(cluster)
        return Cbor.encode(Cbor.Dict(fields))
    }

    private companion object {
        const val FINGERPRINT = 0x12121212L
        val REQUEST_ID = ByteArray(16) { 0x11 }
        val PATH = listOf(PathStep(44, true), PathStep(501, true), PathStep(0, true))

        /** The one signer of [MESSAGE], which is the account key the wallet holds for account 0. */
        val SIGNER = ByteArray(32) { 7 }

        /** A key other than the signer, which a wrong derivation would give. */
        val OTHER_KEY = ByteArray(32) { 0x55 }

        /** A legacy transaction with one signer, [SIGNER], no instructions and a zero blockhash: the smallest message the parser accepts. */
        val MESSAGE: ByteArray = byteArrayOf(1, 0, 0, 1) + SIGNER + ByteArray(32) + byteArrayOf(0)
    }
}
