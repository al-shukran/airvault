package app.lensly.app.sign

import app.lensly.core.evm.EvmFixtures
import app.lensly.core.registry.Erc20Call
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.Registry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.UrScanner
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.Hash
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.Sign
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric

private const val MONAD_TESTNET = 10143L
private const val ETHEREUM = 1L
private const val BASE = 8453L
private const val UNKNOWN_CHAIN = 999L

private const val ART_ADDRESS = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb"
private const val RECIPIENT = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
private const val USDC_ETHEREUM = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
private const val UNREGISTERED = "0x1111111111111111111111111111111111111111"

private const val SELECTOR_TRANSFER = "a9059cbb"
private const val SELECTOR_APPROVE = "095ea7b3"
private const val SELECTOR_BALANCE_OF = "70a08231"
private const val L2_NOTE = "L1 data fee is not computable offline; total may be higher"

private val REQUEST_ID = Numeric.hexStringToByteArray("9b1deb4d3b7d4bad9bdd2b0d7b3dcb6d")
private val ONE_CENT_OF_MON: BigInteger = BigInteger.TEN.pow(16)
private val WALLET by lazy { HdWallet(Mnemonic.toSeed(List(23) { "abandon" } + "art")) }

/** Two accounts, so index 1 is valid and index 2 is not. */
private val VIEW by lazy { WalletView(WALLET.masterFingerprint, accountCount = 2) { WALLET.address(it) } }

private val REGISTRY = Registry(
    networks = listOf(
        NetworkEntry(
            chainId = MONAD_TESTNET,
            name = "Monad Testnet",
            nativeSymbol = "MON",
            nativeDecimals = 18,
            feeModel = FeeModel.L1,
            explorerName = "MonadVision",
            testnet = true,
        ),
        NetworkEntry(
            chainId = ETHEREUM,
            name = "Ethereum",
            nativeSymbol = "ETH",
            nativeDecimals = 18,
            feeModel = FeeModel.L1,
            explorerName = "Etherscan",
            testnet = false,
        ),
        NetworkEntry(
            chainId = BASE,
            name = "Base",
            nativeSymbol = "ETH",
            nativeDecimals = 18,
            feeModel = FeeModel.L2_ROLLUP,
            explorerName = "Basescan",
            testnet = false,
        ),
    ),
    tokens = listOf(
        TokenEntry(ETHEREUM, USDC_ETHEREUM.lowercase(), "USDC", "USD Coin", 6),
    ),
)

class SignPipelineTest {

    private val pipeline = SignPipeline(REGISTRY)

    @Test
    fun eip1559MonTransferIsReviewedAsNativeTransfer() {
        val ready = ready(requestCbor(eip1559Tx()))
        val review = ready.review

        assertEquals("0.01 MON", "${review.amount} ${review.symbol}")
        assertEquals("Native transfer", review.function)
        assertEquals(ART_ADDRESS, review.fromAddress)
        assertEquals("ACCOUNT 1", review.fromAccountLabel)
        assertEquals(RECIPIENT, review.toAddress)
        assertNull(review.tokenContract)
        assertTrue(review.params.isEmpty())
        assertTrue(review.warnings.isEmpty())
        assertEquals(MONAD_TESTNET, review.chainId)
        assertEquals("Monad Testnet", review.networkName)
        assertEquals(REGISTRY.network(MONAD_TESTNET), review.network)
        assertNull(review.token)
        assertEquals(7L, review.nonce)
        assertEquals("EIP-1559", review.txType)
        assertNull(review.feeNote)
        assertEquals(0, ready.accountIndex)
    }

    @Test
    fun eip1559FeeRowsShowGasLimitGasPricesAndMaxFee() {
        val review = ready(requestCbor(eip1559Tx())).review

        assertEquals(
            listOf(
                "Gas limit" to "21000",
                "Max fee per gas" to "30 gwei",
                "Max priority fee" to "1.5 gwei",
                "Max fee" to "0.00063 MON",
            ),
            review.fee,
        )
    }

    @Test
    fun layer2NetworkAddsTheL1FeeNote() {
        val review = ready(requestCbor(eip1559Tx(chainId = BASE), chainId = BASE)).review

        assertEquals("0.01 ETH", "${review.amount} ${review.symbol}")
        assertEquals(L2_NOTE, review.feeNote)
    }

    @Test
    fun legacyTransferShowsGasPriceAndLegacyType() {
        val review = ready(requestCbor(legacyTx(), dataType = 1)).review

        assertEquals("Legacy", review.txType)
        assertEquals(
            listOf("Gas limit" to "60000", "Gas price" to "20 gwei", "Max fee" to "0.0012 MON"),
            review.fee,
        )
    }

    @Test
    fun erc20UsdcTransferUsesTokenDecimals() {
        val data = erc20Call(SELECTOR_TRANSFER, RECIPIENT, BigInteger.valueOf(1_500_000))
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data)
        val review = ready(requestCbor(tx, chainId = ETHEREUM)).review

        assertEquals("1.5 USDC", "${review.amount} ${review.symbol}")
        assertEquals("Token transfer", review.function)
        assertEquals(RECIPIENT, review.toAddress)
        assertEquals(USDC_ETHEREUM, review.tokenContract)
        assertTrue(review.params.isEmpty())
        assertEquals(REGISTRY.network(ETHEREUM), review.network)
        assertEquals(REGISTRY.token(ETHEREUM, USDC_ETHEREUM), review.token)
    }

    @Test
    fun unlimitedErc20ApprovalIsWarned() {
        val data = erc20Call(SELECTOR_APPROVE, RECIPIENT, Erc20Call.MAX_UINT256)
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data)
        val review = ready(requestCbor(tx, chainId = ETHEREUM)).review

        assertEquals("Token approval", review.function)
        assertEquals(listOf("UNLIMITED APPROVAL"), review.warnings)
        assertEquals(listOf("spender", "amount"), review.params.map { it.first })
        assertEquals(RECIPIENT, review.params.first().second)
    }

    @Test
    fun fixedErc20ApprovalHasNoWarning() {
        val data = erc20Call(SELECTOR_APPROVE, RECIPIENT, BigInteger.valueOf(100_000_000))
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data)
        val review = ready(requestCbor(tx, chainId = ETHEREUM)).review

        assertTrue(review.warnings.isEmpty())
        assertEquals("100", review.params.last().second)
    }

    @Test
    fun personalMessageIsReviewedAsTextWhenPrintableAndHexOtherwise() {
        val text = ready(requestCbor("Sign in to Lensly".toByteArray(Charsets.UTF_8), dataType = 3)).review
        assertEquals("Sign message", text.function)
        assertEquals(listOf("message" to "Sign in to Lensly"), text.params)
        assertEquals("Personal message", text.txType)
        assertTrue(text.fee.isEmpty())

        val binary = byteArrayOf(0x00, 0x01, 0xff.toByte())
        assertEquals("0x0001ff", ready(requestCbor(binary, dataType = 3)).review.params.single().second)

        val withControl = "a\u0001b".toByteArray(Charsets.UTF_8)
        assertEquals("0x610162", ready(requestCbor(withControl, dataType = 3)).review.params.single().second)
    }

    @Test
    fun secondAccountIsReviewedWithItsOwnAddress() {
        val signable = ready(requestCbor(eip1559Tx(), index = 1))

        assertEquals(1, signable.accountIndex)
        assertEquals("ACCOUNT 2", signable.review.fromAccountLabel)
        assertEquals(WALLET.address(1), signable.review.fromAddress)
    }

    @Test
    fun requestedAddressMustMatchTheAccountCaseInsensitively() {
        val data = eip1559Tx()

        assertEquals(0, ready(requestCbor(data, address = Numeric.hexStringToByteArray(ART_ADDRESS))).accountIndex)
        assertEquals(
            "This request is for a different address",
            refusal(requestCbor(data, address = Numeric.hexStringToByteArray(WALLET.address(1)))),
        )
    }

    @Test
    fun signedEip1559TransferRecoversToAccountZeroAndEchoesTheRequestId() {
        val signData = eip1559Tx()
        val response = signResponse(ready(requestCbor(signData)))

        assertContentEquals(REQUEST_ID, response.requestId)
        assertEquals(65, response.signature.size)
        val v = response.signature[64].toInt()
        assertTrue(v == 0 || v == 1, "v must be a recovery id, was $v")
        assertEquals(
            EvmFixtures.normalize(ART_ADDRESS),
            recovered(Hash.sha3(signData), response.signature, v),
        )
    }

    @Test
    fun signedLegacyTransferCarriesEip155V() {
        val signData = legacyTx()
        val response = signResponse(ready(requestCbor(signData, dataType = 1)))

        // v = 10143 * 2 + 35 + recId is 20321 or 20322, which needs two bytes.
        assertEquals(66, response.signature.size)
        val v = BigInteger(1, response.signature.copyOfRange(64, 66)).toInt()
        assertTrue(v == 20321 || v == 20322, "unexpected v $v")
        assertEquals(
            EvmFixtures.normalize(ART_ADDRESS),
            recovered(Hash.sha3(signData), response.signature, v - 20321),
        )
    }

    @Test
    fun signedPersonalMessageRecoversWithTheEthereumMessageHash() {
        val message = "Sign in to Lensly".toByteArray(Charsets.UTF_8)
        val response = signResponse(ready(requestCbor(message, dataType = 3)))

        val v = response.signature[64].toInt()
        assertTrue(v == 27 || v == 28, "personal message v must be 27 or 28, was $v")
        assertEquals(
            EvmFixtures.normalize(ART_ADDRESS),
            recovered(Sign.getEthereumMessageHash(message), response.signature, v - 27),
        )
    }

    @Test
    fun signRefusesARequestWithoutAnIdAndStillZeroesTheKey() {
        val signable = ready(requestCbor(eip1559Tx(), requestId = null))
        val key = WALLET.privateKey(0)

        assertFailsWith<IllegalArgumentException> { pipeline.sign(signable, key) }
        assertTrue(key.all { it == 0.toByte() }, "the private key must be zeroed even when signing fails")
    }

    @Test
    fun typedDataIsRefused() {
        val typed = "{\"types\":{},\"message\":{}}".toByteArray(Charsets.UTF_8)
        assertEquals("Typed data not supported yet", refusal(requestCbor(typed, dataType = 2)))
    }

    @Test
    fun requestForAnotherWalletIsRefused() {
        val otherWallet = WALLET.masterFingerprint xor 1
        assertEquals(
            "This request is for a different wallet",
            refusal(requestCbor(eip1559Tx(), fingerprint = otherWallet)),
        )
    }

    @Test
    fun unknownNetworkIsRefused() {
        val tx = eip1559Tx(chainId = UNKNOWN_CHAIN)
        assertEquals("Unknown network 999", refusal(requestCbor(tx, chainId = UNKNOWN_CHAIN)))
    }

    @Test
    fun accountOutsideTheWalletOrOffTheAccountPathIsRefused() {
        val data = eip1559Tx()
        val unknownAccount = "Unknown account"

        assertEquals(unknownAccount, refusal(requestCbor(data, index = 2)))
        assertEquals(
            unknownAccount,
            refusal(requestCbor(data, path = listOf(44L to true, 60L to true, 1L to true, 0L to false, 0L to false))),
        )
        assertEquals(
            unknownAccount,
            refusal(requestCbor(data, path = listOf(44L to true, 60L to true, 0L to true, 1L to false, 0L to false))),
        )
        assertEquals(
            unknownAccount,
            refusal(requestCbor(data, path = listOf(44L to true, 60L to true, 0L to true, 0L to false, 0L to true))),
        )
    }

    @Test
    fun unregisteredTokenContractIsRefused() {
        val data = erc20Call(SELECTOR_TRANSFER, RECIPIENT, BigInteger.TEN)
        val tx = eip1559Tx(chainId = ETHEREUM, to = UNREGISTERED, value = BigInteger.ZERO, data = data)

        assertEquals("Unregistered token or contract", refusal(requestCbor(tx, chainId = ETHEREUM)))
    }

    @Test
    fun unknownCalldataOnARegisteredTokenIsRefused() {
        val data = erc20Call(SELECTOR_BALANCE_OF, RECIPIENT, BigInteger.ZERO)
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ZERO, data = data)

        assertEquals("Unknown contract call", refusal(requestCbor(tx, chainId = ETHEREUM)))
    }

    @Test
    fun transactionChainMustMatchTheRequestChain() {
        val tx = legacyTx(chainId = ETHEREUM)

        assertEquals(
            "The transaction is for chain 1, but the request is for chain 10143",
            refusal(requestCbor(tx, dataType = 1, chainId = MONAD_TESTNET)),
        )
    }

    @Test
    fun tokenCallMayNotAlsoSendNativeValue() {
        val data = erc20Call(SELECTOR_TRANSFER, RECIPIENT, BigInteger.TEN)
        val tx = eip1559Tx(chainId = ETHEREUM, to = USDC_ETHEREUM, value = BigInteger.ONE, data = data)

        assertEquals("Token calls must not send native currency", refusal(requestCbor(tx, chainId = ETHEREUM)))
    }

    @Test
    fun signDataOverSixtyFourKibIsRefusedBeforeDecoding() {
        val oversized = EthSignRequest(
            requestId = REQUEST_ID,
            signData = ByteArray(EthSignRequest.MAX_SIGN_DATA_BYTES + 1),
            dataType = DataType.TYPED_TRANSACTION,
            chainId = MONAD_TESTNET,
            derivationPath = accountPath(0),
            sourceFingerprint = WALLET.masterFingerprint,
            address = null,
            origin = null,
        )

        assertEquals(
            "The data to sign is larger than 64 KiB",
            assertIs<Prepared.Refused>(pipeline.prepare(oversized, VIEW)).reason,
        )
        // The same payload arriving as CBOR is refused by the decoder before any transaction parsing.
        assertIs<Prepared.Refused>(prepared(requestCbor(ByteArray(EthSignRequest.MAX_SIGN_DATA_BYTES + 1))))
    }

    private fun prepared(cbor: ByteArray): Prepared = pipeline.prepare(cbor, VIEW)

    private fun ready(cbor: ByteArray): Prepared.Ready = assertIs<Prepared.Ready>(prepared(cbor))

    private fun refusal(cbor: ByteArray): String = assertIs<Prepared.Refused>(prepared(cbor)).reason

    /** The fields of the ur:eth-signature that answers a signed request. */
    private class Response(val requestId: ByteArray, val signature: ByteArray)

    /** Signs [ready] with the account key from the wallet, then reads back the single eth-signature frame. */
    private fun signResponse(ready: Prepared.Ready): Response {
        val key = WALLET.privateKey(ready.accountIndex)
        val frames = pipeline.sign(ready, key)
        assertTrue(key.all { it == 0.toByte() }, "the private key must be zeroed after signing")

        assertEquals(1, frames.size)
        val scanner = UrScanner()
        assertTrue(scanner.receive(frames.single()))
        val ur = assertNotNull(scanner.result())
        assertEquals("eth-signature", ur.type)

        val fields = (Cbor.decode(ur.cborBytes) as Cbor.Dict).entries.associate { (field, value) ->
            (field as Cbor.Uint).value to value
        }
        assertEquals("Lensly", (fields.getValue(3L) as Cbor.Text).value)
        return Response(
            requestId = ((fields.getValue(1L) as Cbor.Tagged).value as Cbor.Bytes).value,
            signature = (fields.getValue(2L) as Cbor.Bytes).value,
        )
    }

    private fun recovered(hash: ByteArray, signature: ByteArray, recId: Int): String =
        EvmFixtures.recoveredAddress(
            hash,
            signature.copyOfRange(0, 32),
            signature.copyOfRange(32, 64),
            recId,
        )

    /** A valid eth-sign-request as a wallet sends it. Pass null for a field to leave it out. */
    private fun requestCbor(
        signData: ByteArray,
        dataType: Long = 4,
        chainId: Long? = MONAD_TESTNET,
        index: Long = 0,
        fingerprint: Int? = WALLET.masterFingerprint,
        address: ByteArray? = null,
        requestId: ByteArray? = REQUEST_ID,
        path: List<Pair<Long, Boolean>> = accountPathPairs(index),
    ): ByteArray = Cbor.encode(
        dict(
            1L to requestId?.let { Cbor.Tagged(37, Cbor.Bytes(it)) },
            2L to Cbor.Bytes(signData),
            3L to Cbor.Uint(dataType),
            4L to chainId?.let { Cbor.Uint(it) },
            5L to Cbor.Tagged(
                304,
                dict(
                    1L to Cbor.Arr(path.flatMap { (step, hardened) -> listOf(Cbor.Uint(step), Cbor.Bool(hardened)) }),
                    2L to fingerprint?.let { Cbor.Uint(it.toLong() and 0xFFFFFFFFL) },
                ),
            ),
            6L to address?.let { Cbor.Bytes(it) },
            7L to Cbor.Text("metamask"),
        ),
    )

    private fun accountPathPairs(index: Long): List<Pair<Long, Boolean>> =
        listOf(44L to true, 60L to true, 0L to true, 0L to false, index to false)

    private fun accountPath(index: Int): List<PathStep> =
        listOf(PathStep(44, true), PathStep(60, true), PathStep(0, true), PathStep(0, false), PathStep(index, false))

    private fun dict(vararg fields: Pair<Long, Cbor?>): Cbor.Dict =
        Cbor.Dict(fields.mapNotNull { (key, value) -> value?.let { Cbor.Uint(key) to it } })

    private fun eip1559Tx(
        chainId: Long = MONAD_TESTNET,
        to: String = RECIPIENT,
        value: BigInteger = ONE_CENT_OF_MON,
        data: String = "0x",
    ): ByteArray = TransactionEncoder.encode(
        RawTransaction.createTransaction(
            chainId,
            BigInteger.valueOf(7),
            BigInteger.valueOf(21_000),
            to,
            value,
            data,
            BigInteger.valueOf(1_500_000_000L),
            BigInteger.valueOf(30_000_000_000L),
        ),
    )

    private fun legacyTx(
        chainId: Long = MONAD_TESTNET,
        to: String = RECIPIENT,
        value: BigInteger = ONE_CENT_OF_MON,
        data: String = "0x",
    ): ByteArray = TransactionEncoder.encode(
        RawTransaction.createTransaction(
            BigInteger.valueOf(3),
            BigInteger.valueOf(20_000_000_000L),
            BigInteger.valueOf(60_000),
            to,
            value,
            data,
        ),
        chainId,
    )

    /** ABI calldata for a call that takes an address and a uint256, such as transfer or approve. */
    private fun erc20Call(selector: String, address: String, amount: BigInteger): String =
        "0x" + selector +
            address.removePrefix("0x").lowercase().padStart(64, '0') +
            amount.toString(16).padStart(64, '0')
}
