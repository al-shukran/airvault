package app.lensly.app.sign

import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.Registry
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

private const val MONAD = 10143L

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
}
