package app.lumio.data.tx

import app.lumio.data.ETH
import app.lumio.data.provider.explorer.ExplorerTx
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.Test

/** The EVM rows of the explorers, mapped to [LumioTx]: direction, fee, status, token identity, and the memo. */
class EvmTxMappingTest {
    private val native = TxAsset(contract = null, symbol = "ETH", name = "Ether", decimals = 18)
    private val me = "0x" + "ab".repeat(20)
    private val other = "0x" + "cd".repeat(20)
    private val hash = "0x" + "1a".repeat(32)
    private val usdc = "0x" + "ee".repeat(20)
    private val block = 18_000_000L

    private fun normal(
        from: String,
        to: String?,
        valueWei: BigInteger = BigInteger.TEN.pow(18),
        failed: Boolean = false,
        input: String = "0x",
        createdContract: String? = null,
    ) = ExplorerTx.Normal(
        hash = hash,
        timestampSec = 1_693_526_411L,
        from = from,
        to = to,
        confirmations = 1_000L,
        blockNumber = block,
        valueWei = valueWei,
        failed = failed,
        gasUsed = BigInteger.valueOf(21_000),
        gasPrice = BigInteger.valueOf(20_000_000_000L),
        input = input,
        createdContract = createdContract,
    )

    private fun token(from: String, to: String?, amount: Long = 1_500_000L, ordinal: Int = 0) = ExplorerTx.Token(
        hash = hash,
        timestampSec = 1_693_526_411L,
        from = from,
        to = to,
        confirmations = 900L,
        blockNumber = block,
        contract = usdc,
        name = "USD Coin",
        symbol = "USDC",
        decimals = 6,
        amount = BigInteger.valueOf(amount),
        ordinal = ordinal,
    )

    private fun map(vararg rows: ExplorerTx, account: String = me): List<LumioTx> = rows.toList().toLumioTxs(account, ETH, native)

    @Test
    fun aTransactionAndATokenTransferCarryTheBlockTheyAreIn() {
        assertEquals(block, map(normal(from = other, to = me)).single().blockNumber)
        assertEquals(block, map(token(from = other, to = me)).single().blockNumber)
    }

    @Test
    fun anIncomingTransactionIsInWithTheSenderAsTheCounterpartyAndNoFee() {
        val tx = map(normal(from = other, to = me)).single()

        assertEquals(TxDirection.IN, tx.direction)
        assertEquals(other, tx.counterparty)
        assertNull(tx.fee)
        assertEquals(TxStatus.CONFIRMED, tx.status)
        assertEquals(BigInteger.TEN.pow(18), tx.amount)
        assertEquals(1_693_526_411_000L, tx.timestampMs)
        assertEquals(native, tx.asset)
    }

    @Test
    fun anOutgoingTransactionIsOutAndCarriesTheGasItUsed() {
        val tx = map(normal(from = me, to = other)).single()

        assertEquals(TxDirection.OUT, tx.direction)
        assertEquals(other, tx.counterparty)
        assertEquals(BigInteger.valueOf(21_000) * BigInteger.valueOf(20_000_000_000L), tx.fee)
    }

    @Test
    fun aTransactionToTheAccountItselfIsSelfWithoutACounterparty() {
        val tx = map(normal(from = me, to = me)).single()

        assertEquals(TxDirection.SELF, tx.direction)
        assertNull(tx.counterparty)
        assertEquals(BigInteger.valueOf(21_000) * BigInteger.valueOf(20_000_000_000L), tx.fee)
    }

    @Test
    fun aRevertedTransactionIsFailed() {
        assertEquals(TxStatus.FAILED, map(normal(from = me, to = other, failed = true)).single().status)
    }

    @Test
    fun aContractCreationIsOutToTheContractItCreated() {
        val created = "0x" + "99".repeat(20)
        val tx = map(normal(from = me, to = null, createdContract = created)).single()

        assertEquals(TxDirection.OUT, tx.direction)
        assertEquals(created, tx.counterparty)
    }

    @Test
    fun aTokenTransferCarriesItsAssetAndHasNoFeeOfItsOwn() {
        val tx = map(token(from = other, to = me)).single()

        assertEquals(TxDirection.IN, tx.direction)
        assertEquals(TxAsset(usdc, "USDC", "USD Coin", 6), tx.asset)
        assertEquals(BigInteger.valueOf(1_500_000), tx.amount)
        assertNull(tx.fee)
        assertEquals(other, tx.counterparty)
        assertNull(tx.memo)
    }

    @Test
    fun twoIdenticalTokenTransfersOfOneTransactionHaveDifferentIds() {
        val rows = map(token(from = me, to = other, ordinal = 0), token(from = me, to = other, ordinal = 1))

        assertNotEquals(rows[0].id, rows[1].id)
    }

    @Test
    fun theIdNamesTheNetworkAndTheHash() {
        assertEquals("EVM:1:$hash", map(normal(from = me, to = other)).single().id)
    }

    @Test
    fun aTextMessageInTheInputIsTheMemo() {
        val input = "0x" + "Rent for March".toByteArray().joinToString("") { "%02x".format(it) }
        val tx = map(normal(from = other, to = me, input = input)).single()

        assertEquals("Rent for March", tx.memo)
        assertEquals(input, tx.inputData)
    }

    @Test
    fun aCallOrBinaryInputGivesNoMemoButIsKept() {
        val call = map(normal(from = me, to = other, input = "0xa9059cbb0000000000000000000000001234")).single()

        assertNull(call.memo)
        assertEquals("0xa9059cbb0000000000000000000000001234", call.inputData)
        assertNull(map(normal(from = me, to = other, input = "0xdeadbeef")).single().memo)
    }

    @Test
    fun noInputGivesNoInputDataAndNoMemo() {
        val tx = map(normal(from = me, to = other, input = "0x")).single()

        assertNull(tx.inputData)
        assertNull(tx.memo)
    }

    @Test
    fun aLongInputGivesNoMemo() {
        val long = "0x" + "41".repeat(1_001)

        assertNull(map(normal(from = other, to = me, input = long)).single().memo)
    }

    @Test
    fun theAccountsCaseDoesNotChangeTheDirection() {
        val upper = "0x" + "AB".repeat(20)

        assertEquals(TxDirection.OUT, map(normal(from = me, to = other), account = upper).single().direction)
    }
}
