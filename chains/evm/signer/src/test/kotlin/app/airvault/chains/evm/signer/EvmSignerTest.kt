package app.airvault.chains.evm.signer

import app.airvault.chains.evm.evmManifest
import app.airvault.core.model.AddressKind
import app.airvault.core.model.AddressResult
import app.airvault.core.model.Approval
import app.airvault.core.model.ChainId
import app.airvault.core.model.Coverage
import app.airvault.core.model.Curve
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.ReviewSection
import app.airvault.core.model.checkLabels
import app.airvault.core.model.text
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.EcdsaSignature
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.KeyScopeViolation
import app.airvault.core.signer.Path
import app.airvault.core.signer.PublicKey
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.ScopedKeyAccess
import app.airvault.core.signer.SignResponse
import app.airvault.core.signer.allows
import app.airvault.core.signer.SignerRef
import app.lensly.core.evm.Secp256k1
import app.lensly.core.evm.TxSigner
import app.lensly.core.evm.TypedDataSigner
import app.lensly.core.registry.Registry
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PathStep
import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.Sign
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric

class EvmSignerTest {
    private val chain = evmManifest.networks.first().chainId

    @Test
    fun theAddressCodecGivesTheChecksumAddressOfAnUncompressedKey() {
        // The uncompressed public key of private key 1. Its Ethereum address is the one below, in EIP-55 form.
        val key = ("04" + "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798" +
            "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8")
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val address = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf"
        assertEquals(address, EvmSigner.addresses.fromPublicKey(chain, key, AddressKind.DEFAULT))
        assertEquals(AddressResult.Valid(address, AddressKind.DEFAULT), EvmSigner.addresses.parse(chain, address))
    }

    /** On Monad testnet, 10143, v is 20321 or 20322 and needs two bytes. The sign path keeps both, as TxSigner gives them. */
    @Test
    fun aTransactionOnMonadTestnetKeepsItsTwoByteVAndIsSignedAsTxSignerSignsIt() {
        val unsigned = TransactionEncoder.encode(legacyTx(), MONAD)
        val tx = assertIs<UnsignedTx.Result.Ok>(UnsignedTx.decode(unsigned, DataType.TRANSACTION)).tx
        val keys = FakeKeys(KEY)

        val signature = signatureOf(EvmSigner.sign(requestOf(unsigned, MONAD), approvalOf(MONAD, senderOf(KEY)), keys))

        assertContentEquals(TxSigner.signatureOf(unsigned, tx, keySigner(KEY)), signature)
        assertEquals(66, signature.size)
        val v = BigInteger(1, signature.copyOfRange(64, 66)).toInt()
        assertTrue(v == 20321 || v == 20322, "v must be 20321 or 20322, was $v")
    }

    /** The key signs at the path the request names, so account 3 of the wallet is signed with m/44'/60'/3'/0/0. */
    @Test
    fun aRequestIsSignedWithTheKeyOfTheAccountItsPathNames() {
        val keys = FakeKeys(KEY)

        EvmSigner.sign(requestOf(legacyPayload(), MONAD, path = account(3)), approvalOf(MONAD, senderOf(KEY)), keys)

        assertEquals(listOf(Path(account(3))), keys.signedPaths)
    }

    /** The approved sender is the account of the review. A key of another account signs nothing. */
    @Test
    fun aKeyThatIsNotTheReviewedSenderSignsNothing() {
        val keys = FakeKeys(KEY)

        val refusal = assertFailsWith<IllegalArgumentException> {
            EvmSigner.sign(requestOf(legacyPayload(), MONAD), approvalOf(MONAD, senderOf(OTHER_KEY)), keys)
        }

        assertEquals("Key does not match account", refusal.message)
        assertTrue(keys.signedPaths.isEmpty(), "A refused request signs nothing")
    }

    /** A path outside the EVM key scope is refused by the core, before any key signs. */
    /**
     * The BIP44 address keys m/44'/60'/0'/0/N are in the EVM key scope, as the account keys are. Which N a wallet holds is for its key access to say.
     */
    @Test
    fun aBip44AddressKeyIsInsideTheEvmKeyScope() {
        val addressKey = listOf(
            PathStep(44, hardened = true),
            PathStep(60, hardened = true),
            PathStep(0, hardened = true),
            PathStep(0, hardened = false),
            PathStep(2, hardened = false),
        )
        assertTrue(evmManifest.keys.allows(Curve.SECP256K1, Path(addressKey)))
    }

    @Test
    fun aPathOutsideTheEvmKeyScopeThrowsKeyScopeViolationBeforeAnyKeySigns() {
        val keys = FakeKeys(KEY)
        val scoped = ScopedKeyAccess(scopes = evmManifest.keys, delegate = keys)
        val outside = listOf(
            PathStep(44, hardened = true),
            PathStep(60, hardened = true),
            PathStep(0, hardened = true),
            PathStep(1, hardened = false),
            PathStep(0, hardened = false),
        )

        assertFailsWith<KeyScopeViolation> {
            EvmSigner.sign(requestOf(legacyPayload(), MONAD, path = outside), approvalOf(MONAD, senderOf(KEY)), scoped)
        }

        assertTrue(keys.signedPaths.isEmpty())
    }

    /** The approval must be of the chain the request is for. An approval of another chain signs nothing, even one whose sender is the right key. */
    @Test
    fun anApprovalOfAnotherChainSignsNothing() {
        val keys = FakeKeys(KEY)

        val refusal = assertFailsWith<IllegalArgumentException> {
            EvmSigner.sign(requestOf(legacyPayload(), MONAD), approvalOf(1L, senderOf(KEY)), keys)
        }

        assertEquals("The approval is for another chain.", refusal.message)
        assertTrue(keys.signedPaths.isEmpty(), "A refused request signs nothing")
    }

    @Test
    fun aRequestWithNoRequestIdIsRefused() {
        val keys = FakeKeys(KEY)

        assertFailsWith<IllegalArgumentException> {
            EvmSigner.sign(requestOf(legacyPayload(), MONAD, requestId = null), approvalOf(MONAD, senderOf(KEY)), keys)
        }

        assertTrue(keys.signedPaths.isEmpty())
    }

    /** A typed request signs when the approved review shows the domain separator and the message hash that it signs, each in full. */
    @Test
    fun aTypedRequestSignsWhenTheReviewShowsTheHashesItSigns() {
        val request = requestOf(agentJson(MONAD), MONAD, dataType = DataType.TYPED_DATA)
        val approval = tickedApproval(reviewOf(request))
        val typed = typedDataOf(request)

        val signature = signatureOf(EvmSigner.sign(request, approval, FakeKeys(KEY)))

        assertEquals(hashText(typed.domainSeparator()), shownBy(approval, DOMAIN_HASH_LABEL))
        assertEquals(hashText(typed.messageHash()), shownBy(approval, MESSAGE_HASH_LABEL))
        assertContentEquals(TypedDataSigner.signatureOf(typed, keySigner(KEY)), signature)
    }

    /** A Hyperliquid action names chain 1337 in its domain and 42161 in its header. It signs on the domain's chain, which the review reads. */
    @Test
    fun aTypedRequestSignsOnTheChainItsDomainNamesWhenTheHeaderNamesAnother() {
        val request = requestOf(agentJson(HYPERLIQUID_CHAIN), ARBITRUM, dataType = DataType.TYPED_DATA)
        val approval = tickedApproval(reviewOf(request))

        val signature = signatureOf(EvmSigner.sign(request, approval, FakeKeys(KEY)))

        assertEquals(ChainId("eip155:$HYPERLIQUID_CHAIN"), approval.review.chain)
        assertContentEquals(TypedDataSigner.signatureOf(typedDataOf(request), keySigner(KEY)), signature)
    }

    /** A review that shows another domain separator than the one the request signs is refused, and nothing is signed. */
    @Test
    fun aTypedRequestIsRefusedWhenTheReviewShowsAnotherDomainHash() {
        val request = requestOf(agentJson(MONAD), MONAD, dataType = DataType.TYPED_DATA)
        val altered = reviewOf(request).withRows { row ->
            if (row.label == DOMAIN_HASH_LABEL) row.copy(value = DisplayValue.Text(OTHER_HASH, mono = true)) else row
        }

        assertRefusedWithoutSigning(request, tickedApproval(altered))
    }

    /** A review that shows another message hash than the one the request signs is refused, and nothing is signed. */
    @Test
    fun aTypedRequestIsRefusedWhenTheReviewShowsAnotherMessageHash() {
        val request = requestOf(agentJson(MONAD), MONAD, dataType = DataType.TYPED_DATA)
        val altered = reviewOf(request).withRows { row ->
            if (row.label == MESSAGE_HASH_LABEL) row.copy(value = DisplayValue.Text(OTHER_HASH, mono = true)) else row
        }

        assertRefusedWithoutSigning(request, tickedApproval(altered))
    }

    /** A review without a hash row has nothing to check the signed hash against, so it signs nothing. */
    @Test
    fun aTypedRequestIsRefusedWhenTheReviewLacksAHashRow() {
        val request = requestOf(agentJson(MONAD), MONAD, dataType = DataType.TYPED_DATA)
        val missing = reviewOf(request).withRows { row -> row.takeUnless { it.label == MESSAGE_HASH_LABEL } }

        assertRefusedWithoutSigning(request, tickedApproval(missing))
    }

    /** The request as the parser makes it: its body is the parsed request, and its header names the path the request names. */
    private fun requestOf(
        signData: ByteArray,
        chainId: Long,
        path: List<PathStep> = emptyList(),
        requestId: ByteArray? = REQUEST_ID,
        dataType: DataType = DataType.TRANSACTION,
    ): AppRequest {
        val request = EthSignRequest(requestId, signData, dataType, chainId, path, null, null, null)
        val header = RequestHeader(
            app = evmManifest.id,
            chain = ChainId("eip155:$chainId"),
            kind = RequestKind.TRANSACTION,
            signers = listOf(SignerRef(request.sourceFingerprint, request.derivationPath)),
            requestId = requestId,
        )
        return AppRequest(header, EvmRequestBody.encode(EvmRequestParser.parsedOf(request)))
    }

    /** The approval of a review that names [sender] as its From, with no warning to tick. */
    private fun approvalOf(chainId: Long, sender: String): Approval {
        val chain = ChainId("eip155:$chainId")
        val review = Review(
            title = "Test",
            chain = chain,
            network = NetworkLabel(name = "Test", testnet = true, detail = null),
            summary = emptyList(),
            facts = emptyList(),
            sections = listOf(ReviewSection("Request", listOf(ReviewRow(FROM_LABEL, DisplayValue.Address(chain, sender), null)))),
            warnings = emptyList(),
            coverage = Coverage(decoded = emptySet(), excluded = emptyMap()),
        )
        return requireNotNull(Approval.of(review, emptySet()))
    }

    /** The review EvmReviewBuilder builds for [request] from the account of [KEY]. */
    private fun reviewOf(request: AppRequest): Review =
        assertIs<EvmReviewResult.Ok>(REVIEWS.review(EvmRequestBody.decode(request.body), senderOf(KEY))).review

    /** The approval of [review] by an owner who ticks every check it names. */
    private fun tickedApproval(review: Review): Approval = requireNotNull(Approval.of(review, review.checkLabels().toSet()))

    /** The text of the one row named [label] that [approval] shows. */
    private fun shownBy(approval: Approval, label: String): String =
        approval.review.sections.flatMap { it.rows }.single { it.label == label }.value.text()

    /** [this] review with each row passed through [transform], which returns null to drop the row. */
    private fun Review.withRows(transform: (ReviewRow) -> ReviewRow?): Review =
        copy(sections = sections.map { section -> section.copy(rows = section.rows.mapNotNull(transform)) })

    /** The typed data of [request], as the parser reads it. */
    private fun typedDataOf(request: AppRequest): TypedData =
        assertIs<TypedDataParser.Result.Ok>(EvmRequestBody.decode(request.body).typed).data

    /** Signs [request] with [approval], and asserts that the refusal names the hashes and that no key signs. */
    private fun assertRefusedWithoutSigning(request: AppRequest, approval: Approval) {
        val keys = FakeKeys(KEY)

        val refusal = assertFailsWith<IllegalArgumentException> { EvmSigner.sign(request, approval, keys) }

        assertEquals(HASHES_REFUSAL, refusal.message)
        assertTrue(keys.signedPaths.isEmpty(), "A refused request signs nothing")
    }

    /** The signature in the response payload, which is key 2 of its map. */
    private fun signatureOf(response: SignResponse): ByteArray {
        assertEquals(EvmSignResponse.UR_TYPE, response.urType)
        val fields = assertIs<Cbor.Dict>(Cbor.decode(response.cbor)).entries
        return (fields[1].second as Cbor.Bytes).value
    }

    private fun legacyPayload(): ByteArray = TransactionEncoder.encode(legacyTx(), MONAD)

    private fun legacyTx(): RawTransaction = RawTransaction.createTransaction(
        BigInteger.valueOf(3),
        BigInteger.valueOf(20_000_000_000L),
        BigInteger.valueOf(60_000),
        TO,
        BigInteger.ZERO,
        "0x",
    )

    /** The digest signer of [key] held at the Single Key path, through the key access the sign path signs with. */
    private fun keySigner(key: ByteArray): (ByteArray) -> EcdsaSignature {
        val keys = FakeKeys(key)
        return { digest -> keys.signEcdsaSecp256k1(EvmSigner.singleKeyPath, digest) }
    }

    /** Key access that holds [key] for every path, and records the path of each digest it signs. */
    private class FakeKeys(private val key: ByteArray) : KeyAccess {
        val signedPaths = mutableListOf<Path>()

        override fun publicKey(curve: Curve, path: Path): PublicKey = PublicKey(curve, compressed(key))

        override fun signEcdsaSecp256k1(path: Path, digest32: ByteArray): EcdsaSignature {
            signedPaths += path
            return Secp256k1.sign(digest32, key)
        }

        override fun signSchnorrBip340(path: Path, digest32: ByteArray, tweak: ByteArray?): ByteArray = error("not used by EVM")

        override fun signEd25519(curve: Curve, path: Path, message: ByteArray): ByteArray = error("not used by EVM")

        override fun <T> withPrivateKey(curve: Curve, path: Path, block: (ByteArray) -> T): T = error("not used by EVM")
    }

    private companion object {
        /** The EIP-155 specification's example key, which has never held funds. */
        val KEY: ByteArray = Numeric.hexStringToByteArray("0x" + "46".repeat(32))
        val OTHER_KEY: ByteArray = ByteArray(32) { 0x55 }
        val REQUEST_ID = ByteArray(16) { 0x11 }
        const val TO = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
        const val MONAD = 10143L
        const val ARBITRUM = 42161L
        const val HYPERLIQUID_CHAIN = 1337L
        const val HASHES_REFUSAL = "The review does not show the hashes of the typed data it signs."

        /** A hash that no review of the fixtures below shows. */
        val OTHER_HASH: String = hashText(ByteArray(32) { 0x11 })

        /** The review of the typed data of this file: no registry entry, and no call to decode. */
        val REVIEWS = EvmReviewBuilder(Registry(networks = emptyList(), tokens = emptyList()), { _, _ -> error("typed data decodes no call") })

        /** The typed message of a Hyperliquid phantom agent: an Agent of a source and a connection ID, on the Exchange domain of [domainChain]. */
        fun agentJson(domainChain: Long): ByteArray = (
            """{"types":{"Agent":[{"name":"source","type":"string"},{"name":"connectionId","type":"bytes32"}],""" +
                """"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},""" +
                """{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}]},""" +
                """"primaryType":"Agent","domain":{"chainId":$domainChain,"name":"Exchange","verifyingContract":"$TO","version":"1"},""" +
                """"message":{"source":"a","connectionId":"0x${"ab".repeat(32)}"}}"""
            ).toByteArray(Charsets.UTF_8)

        /** The EVM account path m/44'/60'/[index]'/0/0. */
        fun account(index: Int): List<PathStep> = listOf(
            PathStep(44, hardened = true),
            PathStep(60, hardened = true),
            PathStep(index, hardened = true),
            PathStep(0, hardened = false),
            PathStep(0, hardened = false),
        )

        /** The EIP-55 address of [key]. */
        fun senderOf(key: ByteArray): String = Keys.toChecksumAddress(Keys.getAddress(ECKeyPair.create(key).publicKey))

        /** The 33-byte compressed public key of the private key [key]. */
        fun compressed(key: ByteArray): ByteArray = Sign.CURVE_PARAMS.g.multiply(BigInteger(1, key)).normalize().getEncoded(true)
    }
}
