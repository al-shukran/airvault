package app.lensly.app.sign

import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.Registry
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.ui.sign.SIGN_MESSAGE_FUNCTION
import app.lensly.ui.sign.TxReview
import app.lensly.ui.sign.UNKNOWN_CHAIN_NAME
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

private const val MONAD = 10143L
private const val SENDER = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb"
private const val RECIPIENT = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
private const val TOKEN = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"

private val REGISTRY = Registry(
    networks = listOf(
        NetworkEntry(
            chainId = MONAD,
            name = "Monad Testnet",
            nativeSymbol = "MON",
            nativeDecimals = 18,
            feeModel = FeeModel.L1,
            explorerName = "MonadVision",
            testnet = true,
        ),
    ),
    tokens = emptyList(),
)

private fun request(
    dataType: DataType = DataType.TRANSACTION,
    chainId: Long? = MONAD,
    signData: ByteArray = byteArrayOf(1, 2, 3),
) = EthSignRequest(
    requestId = ByteArray(16) { 7 },
    signData = signData,
    dataType = dataType,
    chainId = chainId,
    derivationPath = emptyList(),
    sourceFingerprint = null,
    address = null,
    origin = "https://app.example",
)

private fun transferReview() = TxReview(
    networkName = "Monad Testnet",
    chainId = MONAD,
    fromAccountLabel = "SEED WALLET 3",
    fromAddress = SENDER,
    toAddress = RECIPIENT,
    amount = "1.5",
    symbol = "USDC",
    tokenContract = TOKEN,
    function = "transfer",
    params = listOf("to" to RECIPIENT, "value" to "1500000 (1.5 USDC)"),
    fee = listOf("Gas limit" to "21000", "Max fee" to "0.0021 MON"),
    feeNote = "L1 data fee is not computable offline; total may be higher",
    warnings = emptyList(),
    nonce = 4L,
    txType = "EIP-1559",
)

class ReviewRecordTest {

    @Test
    fun aSignedTransactionKeepsTheFactsTheOwnerSaw() {
        val request = request(signData = byteArrayOf(9, 8, 7))
        val ready = Prepared.Ready(transferReview(), request, accountIndex = 3, walletId = "wallet")

        val record = reviewRecordOf(
            ReviewEntry("wallet", ReviewOutcome.SIGNED, request, ready, refusal = null),
            atMs = 42L,
            registry = REGISTRY,
        )

        assertEquals(42L, record.atMs)
        assertEquals(ReviewOutcome.SIGNED, record.outcome)
        assertEquals(ReviewKind.TRANSACTION, record.kind)
        assertEquals(MONAD, record.chainId)
        assertEquals("Monad Testnet", record.networkName)
        assertEquals(3, record.accountIndex)
        assertEquals(SENDER, record.address)
        assertEquals("transfer", record.function)
        assertEquals(RECIPIENT, record.to)
        assertEquals("1.5 USDC", record.amount)
        assertEquals(TOKEN, record.tokenContract)
        assertEquals(
            listOf(
                "Gas limit" to "21000",
                "Max fee" to "0.0021 MON",
                "Note" to "L1 data fee is not computable offline; total may be higher",
            ),
            record.fee,
        )
        assertEquals(transferReview().params, record.params)
        assertEquals("https://app.example", record.origin)
        assertNull(record.refusal)
        assertEquals(sha256Hex(byteArrayOf(9, 8, 7)), record.dataHash)
    }

    @Test
    fun aRefusalBeforeAnyReviewNamesTheChainAndKeepsTheReason() {
        val record = reviewRecordOf(
            ReviewEntry("wallet", ReviewOutcome.REFUSED, request(), null, refusal = "Unknown contract call"),
            atMs = 1L,
            registry = REGISTRY,
        )

        assertEquals(ReviewOutcome.REFUSED, record.outcome)
        assertEquals("Monad Testnet", record.networkName)
        assertNull(record.accountIndex)
        assertNull(record.address)
        assertNull(record.function)
        assertTrue(record.fee.isEmpty())
        assertEquals("Unknown contract call", record.refusal)
    }

    @Test
    fun aChainOutsideTheRegistryIsNamedAsUnknown() {
        val record = reviewRecordOf(
            ReviewEntry("wallet", ReviewOutcome.REFUSED, request(chainId = 999L), null, refusal = "Unknown chain"),
            atMs = 1L,
            registry = REGISTRY,
        )

        assertEquals(999L, record.chainId)
        assertEquals(UNKNOWN_CHAIN_NAME, record.networkName)
    }

    @Test
    fun aRequestThatNamesNoChainHasNoNetwork() {
        val record = reviewRecordOf(
            ReviewEntry("wallet", ReviewOutcome.REFUSED, request(chainId = null), null, refusal = "No network"),
            atMs = 1L,
            registry = REGISTRY,
        )

        assertNull(record.chainId)
        assertNull(record.networkName)
    }

    @Test
    fun aMessageIsKindMessageWithItsTextAndNoAmountOrRecipient() {
        val message = "Sign in to Lensly".toByteArray()
        val review = transferReview().copy(
            function = SIGN_MESSAGE_FUNCTION,
            toAddress = "",
            amount = "",
            symbol = "",
            tokenContract = null,
            params = listOf("message" to "Sign in to Lensly"),
            fee = emptyList(),
            feeNote = null,
            txType = "Personal message",
        )
        val request = request(dataType = DataType.PERSONAL_MESSAGE, signData = message)

        val record = reviewRecordOf(
            ReviewEntry("wallet", ReviewOutcome.SIGNED, request, Prepared.Ready(review, request, 0, "wallet"), refusal = null),
            atMs = 1L,
            registry = REGISTRY,
        )

        assertEquals(ReviewKind.MESSAGE, record.kind)
        assertEquals(SIGN_MESSAGE_FUNCTION, record.function)
        assertNull(record.to)
        assertNull(record.amount)
        assertEquals(listOf("message" to "Sign in to Lensly"), record.params)
        assertEquals(sha256Hex(message), record.dataHash)
    }

    @Test
    fun typedDataIsKindMessageToo() {
        val record = reviewRecordOf(
            ReviewEntry(
                "wallet",
                ReviewOutcome.REFUSED,
                request(dataType = DataType.TYPED_DATA),
                null,
                refusal = "Typed data not supported yet",
            ),
            atMs = 1L,
            registry = REGISTRY,
        )

        assertEquals(ReviewKind.MESSAGE, record.kind)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
