package app.lensly.core.evm

import app.airvault.chains.evm.evmManifest
import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.evm.signer.EvmRequestBody
import app.airvault.chains.evm.signer.EvmRequestParser
import app.airvault.chains.evm.signer.EvmReviewBuilder
import app.airvault.chains.evm.signer.EvmReviewResult
import app.airvault.chains.evm.signer.EvmSigner
import app.airvault.chains.evm.signer.ParsedRequest
import app.airvault.chains.evm.signer.Rlp
import app.airvault.chains.evm.signer.TypedData
import app.airvault.chains.evm.signer.TypedDataParser
import app.airvault.core.model.Approval
import app.airvault.core.model.ChainId
import app.airvault.core.model.Coverage
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.ReviewSection
import app.airvault.core.model.checkLabels
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.ScopedKeyAccess
import app.airvault.core.signer.SignerRef
import app.lensly.core.registry.Registry
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.SingleKeyAccess
import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder

/**
 * Each signing vector of the sign tests (TxSignerTest, Eip155VectorTest, Eip1559VectorTest, MessageSignerTest and TypedDataSignerTest) is signed
 * through the one sign path: EvmSigner.sign over a Single Key's key access, scoped as the core scopes it. The signature the path answers with is
 * the signature the app's signers give for the same key, byte for byte.
 */
class EvmSignerIdentityTest {
    private val artKey = EvmFixtures.artKey

    @Test
    fun thePublishedEip155SignatureIsTheSignatureOfTheSignPath() {
        val vector = PublishedSigningVectors.eip155()
        val unsigned = EvmFixtures.hex(vector.unsigned)
        val key = EvmFixtures.hex(vector.privateKey)

        assertContentEquals(
            signedByTheSigner(key, DataType.TRANSACTION, unsigned),
            signedByThePath(key, unsigned, DataType.TRANSACTION, chainId = 1L),
        )
    }

    @Test
    fun eachPublishedEip1559SignatureIsTheSignatureOfTheSignPath() {
        for (vector in PublishedSigningVectors.eip1559()) {
            val key = EvmFixtures.hex(vector.privateKey)
            val unsigned = unsignedOf(vector)

            assertContentEquals(
                signedByTheSigner(key, DataType.TYPED_TRANSACTION, unsigned),
                signedByThePath(key, unsigned, DataType.TYPED_TRANSACTION, chainId = vector.chainId),
                "nonce ${vector.nonce}",
            )
        }
    }

    /** On Monad testnet, 10143, v is 20321 or 20322 and needs two bytes. The sign path keeps both bytes, as the app's signer does. */
    @Test
    fun aLegacyTransactionOnMonadTestnetHasTheSameTwoByteSignatureOnTheSignPath() {
        val raw = RawTransaction.createTransaction(
            BigInteger.valueOf(3),
            BigInteger.valueOf(20_000_000_000L),
            BigInteger.valueOf(60_000),
            TO,
            BigInteger.ZERO,
            "0x",
        )
        val unsigned = TransactionEncoder.encode(raw, MONAD)

        val signature = signedByThePath(artKey, unsigned, DataType.TRANSACTION, chainId = MONAD)

        assertContentEquals(signedByTheSigner(artKey, DataType.TRANSACTION, unsigned), signature)
        assertEquals(66, signature.size)
        val v = BigInteger(1, signature.copyOfRange(64, 66)).toInt()
        assertTrue(v == 20321 || v == 20322, "v must be 20321 or 20322, was $v")
    }

    @Test
    fun aPersonalMessageHasTheSameSignatureOnTheSignPath() {
        val message = "Lensly sign-in".toByteArray(Charsets.UTF_8)

        assertContentEquals(
            signedByTheSigner(artKey, DataType.PERSONAL_MESSAGE, message),
            signedByThePath(artKey, message, DataType.PERSONAL_MESSAGE, chainId = null),
        )
    }

    @Test
    fun typedDataHasTheSameSignatureOnTheSignPath() {
        val json = TYPED_NOTE.toByteArray(Charsets.UTF_8)

        assertContentEquals(
            signedByTheSigner(artKey, DataType.TYPED_DATA, json),
            signedByThePath(artKey, json, DataType.TYPED_DATA, chainId = null),
        )
    }

    /** A Single Key holds one account, so a request that names another account's path is refused by its key access. */
    @Test
    fun aRequestNamingAnotherAccountThanTheSingleKeysIsRefused() {
        val message = "Lensly sign-in".toByteArray(Charsets.UTF_8)

        assertFailsWith<IllegalArgumentException> {
            signedByThePath(artKey, message, DataType.PERSONAL_MESSAGE, chainId = null, derivationPath = account(1))
        }
    }

    /** The signature the app's signer gives [signData] under [key] today, which the sign path must match. */
    private fun signedByTheSigner(key: ByteArray, dataType: DataType, signData: ByteArray): ByteArray = when (dataType) {
        DataType.TRANSACTION, DataType.TYPED_TRANSACTION ->
            assertIs<TxSignResult.Signed>(TxSigner.sign(signData, dataType, key)).signature
        DataType.PERSONAL_MESSAGE ->
            assertIs<MessageSigner.Result.Signed>(MessageSigner.sign(dataType, signData, key)).signature
        DataType.TYPED_DATA -> {
            val typed = typedOf(signData)
            assertIs<TypedDataSigner.Result.Signed>(TypedDataSigner.sign(dataType, typed, typed.domainSeparator(), key)).signature
        }
    }

    /**
     * The signature the sign path answers with for [signData], signed by a Single Key holding [key]. The request is built as the parser builds
     * it: its body is the parsed request, and its header names [derivationPath], which a Single Key's request leaves empty.
     */
    private fun signedByThePath(
        key: ByteArray,
        signData: ByteArray,
        dataType: DataType,
        chainId: Long?,
        derivationPath: List<PathStep> = emptyList(),
    ): ByteArray {
        val request = EthSignRequest(REQUEST_ID, signData, dataType, chainId, derivationPath, null, null, null)
        val chain = ChainId("eip155:${chainId ?: DEFAULT_CHAIN}")
        val header = RequestHeader(
            app = evmManifest.id,
            chain = chain,
            kind = kindOf(dataType),
            signers = listOf(SignerRef(null, derivationPath)),
            requestId = REQUEST_ID,
        )
        val parsed = EvmRequestParser.parsedOf(request)
        val sender = AccountAddresses.addressOfPrivateKey(key)
        val review = if (dataType == DataType.TYPED_DATA) typedReviewOf(parsed, sender) else reviewOf(chain, sender)
        val approval = requireNotNull(Approval.of(review, review.checkLabels().toSet()))
        val body = EvmRequestBody.encode(parsed)
        val keys = ScopedKeyAccess(evmManifest.keys, SingleKeyAccess(key, EvmSigner.singleKeyPath))
        return signatureOf(EvmSigner.sign(AppRequest(header, body), approval, keys).cbor)
    }

    /** The signature in an eth-signature payload, which is key 2 of its map. */
    private fun signatureOf(cbor: ByteArray): ByteArray {
        val fields = assertIs<Cbor.Dict>(Cbor.decode(cbor)).entries
        return (fields[1].second as Cbor.Bytes).value
    }

    /** The review the builder gives typed data [parsed] from [sender], which shows the hashes the signature covers. */
    private fun typedReviewOf(parsed: ParsedRequest, sender: String): Review {
        val builder = EvmReviewBuilder(Registry(networks = emptyList(), tokens = emptyList()), { _, _ -> error("typed data decodes no call") })
        return assertIs<EvmReviewResult.Ok>(builder.review(parsed, sender)).review
    }

    /** A review that names [sender] as its From and warns about nothing, which the sign path needs to approve a request. */
    private fun reviewOf(chain: ChainId, sender: String): Review = Review(
        title = "Identity",
        chain = chain,
        network = NetworkLabel(name = "Identity", testnet = true, detail = null),
        summary = emptyList(),
        facts = emptyList(),
        sections = listOf(ReviewSection("Request", listOf(ReviewRow("From", DisplayValue.Address(chain, sender), null)))),
        warnings = emptyList(),
        coverage = Coverage(decoded = emptySet(), excluded = emptyMap()),
    )

    private fun kindOf(dataType: DataType): RequestKind = when (dataType) {
        DataType.TRANSACTION, DataType.TYPED_TRANSACTION -> RequestKind.TRANSACTION
        DataType.PERSONAL_MESSAGE -> RequestKind.MESSAGE
        DataType.TYPED_DATA -> RequestKind.TYPED_DATA
    }

    private fun typedOf(json: ByteArray): TypedData = (TypedDataParser.parse(json) as TypedDataParser.Result.Ok).data

    /** The unsigned payload of an EIP-1559 vector: 0x02 and the first eight fields of its signed transaction, then an empty access list. */
    private fun unsignedOf(vector: PublishedSigningVectors.Eip1559): ByteArray {
        val signed = EvmFixtures.hex(vector.signedTx)
        val fields = (Rlp.decode(signed.copyOfRange(1, signed.size)) as Rlp.Lst).items
        val kept = fields.take(8).map { EvmFixtures.rlpString((it as Rlp.Str).bytes) }
        return byteArrayOf(0x02) + EvmFixtures.rlpList(kept + EvmFixtures.rlpList(emptyList()))
    }

    /** The EVM account path m/44'/60'/[index]'/0/0. */
    private fun account(index: Int): List<PathStep> = listOf(
        PathStep(44, hardened = true),
        PathStep(60, hardened = true),
        PathStep(index, hardened = true),
        PathStep(0, hardened = false),
        PathStep(0, hardened = false),
    )

    private companion object {
        /** The EIP-155 signing example's recipient, as TxSignerTest uses it. */
        const val TO = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
        const val MONAD = 10143L

        /** The chain a message or a typed-data domain without a chain names: Ethereum mainnet. */
        const val DEFAULT_CHAIN = 1L

        val REQUEST_ID = ByteArray(16) { 0x11 }

        /** The typed note of TypedDataSignerTest, which names chain 1 in its domain. */
        const val TYPED_NOTE = """{"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"chainId","type":"uint256"}],""" +
            """"Note":[{"name":"text","type":"string"}]},"primaryType":"Note","domain":{"name":"Lensly","chainId":1},""" +
            """"message":{"text":"hello"}}"""
    }
}
