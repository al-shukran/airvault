package app.lensly.app.sign

import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.Registry
import app.lensly.ui.sign.SIGN_MESSAGE_FUNCTION
import app.lensly.ui.sign.TxReview
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

private const val MONAD = 10143L
private const val SENDER = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb"
private const val RECIPIENT = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
private const val CONTRACT_ADDR = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"

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
    tokenContract = CONTRACT_ADDR,
    function = "transfer",
    params = listOf("to" to RECIPIENT, "value" to "1500000 (1.5 USDC)"),
    fee = listOf("Gas Limit" to "21000", "Max Fee" to "0.0021 MON"),
    feeNote = "L1 data fee is not computable offline; total may be higher",
    warnings = emptyList(),
    nonce = 4L,
    txType = "EIP-1559",
)

class ReviewRecordTest {

    @Test
    fun aRefusalBeforeAnyReviewNamesTheChainAndKeepsTheReason() {
        val record = reviewRecordOf(
            ReviewEntry("wallet", ReviewOutcome.REFUSED, request(), null, refusal = "Unknown Contract Call"),
            atMs = 1L,
            registry = REGISTRY,
        )

        assertEquals(ReviewOutcome.REFUSED, record.outcome)
        assertEquals("Monad Testnet", record.networkName)
        assertNull(record.accountIndex)
        assertNull(record.address)
        assertNull(record.function)
        assertTrue(record.fee.isEmpty())
        assertEquals("Unknown Contract Call", record.refusal)
    }

    @Test
    fun aChainOutsideTheRegistryIsNamedAsUnknown() {
        val record = reviewRecordOf(
            ReviewEntry("wallet", ReviewOutcome.REFUSED, request(chainId = 999L), null, refusal = "Unknown chain"),
            atMs = 1L,
            registry = REGISTRY,
        )

        assertEquals(999L, record.chainId)
        assertEquals("Unknown network (chain id 999)", record.networkName)
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
