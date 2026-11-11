package app.lensly.core.evm

import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.DataType
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder

class UnsignedTxTest {

    private val to = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"

    @Test
    fun decodesEip1559FieldsFromWeb3jEncoding() {
        val raw = RawTransaction.createTransaction(
            10143L,
            BigInteger.valueOf(7),
            BigInteger.valueOf(21_000),
            to,
            BigInteger("1000000000000000000"),
            "0x",
            BigInteger.valueOf(1_500_000_000L),
            BigInteger.valueOf(30_000_000_000L),
        )
        val tx = okTx(UnsignedTx.decode(TransactionEncoder.encode(raw), DataType.TYPED_TRANSACTION))

        assertEquals(
            UnsignedTx(
                type = TxType.EIP1559,
                chainId = BigInteger.valueOf(10143),
                nonce = BigInteger.valueOf(7),
                to = to,
                value = BigInteger("1000000000000000000"),
                data = "0x",
                gasLimit = BigInteger.valueOf(21_000),
                gasPrice = null,
                maxPriorityFeePerGas = BigInteger.valueOf(1_500_000_000L),
                maxFeePerGas = BigInteger.valueOf(30_000_000_000L),
            ),
            tx,
        )
    }

    @Test
    fun decodesLegacyEip155FieldsFromWeb3jEncoding() {
        val data = "0xa9059cbb" + "00".repeat(12) + "11".repeat(20) + "00".repeat(31) + "01"
        val raw = RawTransaction.createTransaction(
            BigInteger.valueOf(3),
            BigInteger.valueOf(20_000_000_000L),
            BigInteger.valueOf(60_000),
            to,
            BigInteger.ZERO,
            data,
        )
        val tx = okTx(UnsignedTx.decode(TransactionEncoder.encode(raw, 10143L), DataType.TRANSACTION))

        assertEquals(
            UnsignedTx(
                type = TxType.LEGACY_EIP155,
                chainId = BigInteger.valueOf(10143),
                nonce = BigInteger.valueOf(3),
                to = to,
                value = BigInteger.ZERO,
                data = data,
                gasLimit = BigInteger.valueOf(60_000),
                gasPrice = BigInteger.valueOf(20_000_000_000L),
                maxPriorityFeePerGas = null,
                maxFeePerGas = null,
            ),
            tx,
        )
    }

    @Test
    fun contractCreationDecodesWithNullTo() {
        val raw = RawTransaction.createContractTransaction(
            BigInteger.ZERO,
            BigInteger.valueOf(1_000_000_000L),
            BigInteger.valueOf(100_000),
            BigInteger.ZERO,
            "0x6080",
        )
        val tx = okTx(UnsignedTx.decode(TransactionEncoder.encode(raw, 10143L), DataType.TRANSACTION))
        assertEquals(null, tx.to)
        assertEquals("0x6080", tx.data)
    }

    @Test
    fun eip1559AccessListIsAcceptedAndNotDecoded() {
        val address = EvmFixtures.hex("9858effd232b4033e47d90003d41ec34ecaeda94")
        val key = ByteArray(32) { 1 }
        val accessList = EvmFixtures.rlpList(
            listOf(EvmFixtures.rlpList(listOf(EvmFixtures.rlpString(address), EvmFixtures.rlpList(listOf(EvmFixtures.rlpString(key)))))),
        )
        val payload = byteArrayOf(0x02) + EvmFixtures.rlpList(
            listOf(
                EvmFixtures.uint(10143),
                EvmFixtures.uint(0),
                EvmFixtures.uint(1),
                EvmFixtures.uint(2),
                EvmFixtures.uint(21_000),
                EvmFixtures.rlpString(address),
                EvmFixtures.uint(0),
                EvmFixtures.rlpString(ByteArray(0)),
                accessList,
            ),
        )
        val tx = okTx(UnsignedTx.decode(payload, DataType.TYPED_TRANSACTION))
        assertEquals(to.lowercase(), tx.to?.lowercase())
    }

    @Test
    fun refusesDataTypesOtherThanTransactions() {
        val legacy = TxSamples.legacyPayload()
        assertRejected(UnsignedTx.decode(legacy, DataType.PERSONAL_MESSAGE))
        assertRejected(UnsignedTx.decode(legacy, DataType.TYPED_DATA))
    }

    @Test
    fun refusesEip2930AndOtherTypedPrefixes() {
        val eip1559 = TxSamples.eip1559Payload()
        val eip2930 = byteArrayOf(0x01) + eip1559.copyOfRange(1, eip1559.size)
        assertRejected(UnsignedTx.decode(eip2930, DataType.TYPED_TRANSACTION))
        assertRejected(UnsignedTx.decode(ByteArray(0), DataType.TYPED_TRANSACTION))
    }

    @Test
    fun refusesMalformedLegacyShapes() {
        assertRejected(UnsignedTx.decode(TxSamples.legacy(fields = TxSamples.legacyFields().dropLast(1)), DataType.TRANSACTION))
        assertRejected(UnsignedTx.decode(TxSamples.legacy(placeholderR = EvmFixtures.uint(1)), DataType.TRANSACTION))
        assertRejected(UnsignedTx.decode(TxSamples.legacy(chainId = EvmFixtures.uint(0)), DataType.TRANSACTION))
        assertRejected(UnsignedTx.decode(TxSamples.legacy(nonce = EvmFixtures.rlpString(byteArrayOf(0, 1))), DataType.TRANSACTION))
        assertRejected(UnsignedTx.decode(TxSamples.legacy(to = EvmFixtures.rlpString(ByteArray(19))), DataType.TRANSACTION))
        assertRejected(UnsignedTx.decode(TxSamples.legacy(value = EvmFixtures.rlpString(ByteArray(33) { 1 })), DataType.TRANSACTION))
        assertRejected(UnsignedTx.decode(EvmFixtures.rlpString(byteArrayOf(1, 2, 3)), DataType.TRANSACTION))
    }

    @Test
    fun refusesMalformedEip1559Shapes() {
        val base = TxSamples.eip1559Fields()
        assertRejected(UnsignedTx.decode(TxSamples.eip1559Payload(base.dropLast(1)), DataType.TYPED_TRANSACTION))
        assertRejected(
            UnsignedTx.decode(
                TxSamples.eip1559Payload(base.replaced(8, EvmFixtures.rlpString(ByteArray(0)))),
                DataType.TYPED_TRANSACTION,
            ),
        )
        assertRejected(
            UnsignedTx.decode(TxSamples.eip1559Payload(base.replaced(0, EvmFixtures.uint(0))), DataType.TYPED_TRANSACTION),
        )
    }

    @Test
    fun acceptsCalldataAtTheLimitAndRefusesOneByteMore() {
        val atLimit = calldataTx(ByteArray(ParserLimits.MAX_CALLDATA_BYTES))
        assertEquals(
            "0x" + "00".repeat(ParserLimits.MAX_CALLDATA_BYTES),
            okTx(UnsignedTx.decode(atLimit, DataType.TRANSACTION)).data,
        )

        val overLimit = calldataTx(ByteArray(ParserLimits.MAX_CALLDATA_BYTES + 1))
        val rejected = assertIs<UnsignedTx.Result.Rejected>(UnsignedTx.decode(overLimit, DataType.TRANSACTION))
        assertEquals("calldata is larger than ${ParserLimits.MAX_CALLDATA_BYTES} bytes", rejected.reason)
    }

    @Test
    fun refusesEip1559CalldataPastTheLimit() {
        val oversized = ByteArray(ParserLimits.MAX_CALLDATA_BYTES + 1)
        val payload = TxSamples.eip1559Payload(TxSamples.eip1559Fields().replaced(7, EvmFixtures.rlpString(oversized)))
        val rejected = assertIs<UnsignedTx.Result.Rejected>(UnsignedTx.decode(payload, DataType.TYPED_TRANSACTION))
        assertEquals("calldata is larger than ${ParserLimits.MAX_CALLDATA_BYTES} bytes", rejected.reason)
    }

    /** An unsigned legacy payload whose calldata is [data]. */
    private fun calldataTx(data: ByteArray): ByteArray =
        TxSamples.legacy(fields = TxSamples.legacyFields().replaced(5, EvmFixtures.rlpString(data)))

    private fun okTx(result: UnsignedTx.Result): UnsignedTx = assertIs<UnsignedTx.Result.Ok>(result).tx

    private fun assertRejected(result: UnsignedTx.Result) {
        assertIs<UnsignedTx.Result.Rejected>(result)
    }

    private fun List<ByteArray>.replaced(index: Int, value: ByteArray): List<ByteArray> =
        toMutableList().also { it[index] = value }
}

/** Hand-built sign payloads, for shapes web3j will not produce. */
internal object TxSamples {
    private const val TO = "9858effd232b4033e47d90003d41ec34ecaeda94"

    fun legacyFields(): List<ByteArray> = listOf(
        EvmFixtures.uint(3),
        EvmFixtures.uint(20_000_000_000L),
        EvmFixtures.uint(60_000),
        EvmFixtures.rlpString(EvmFixtures.hex(TO)),
        EvmFixtures.uint(0),
        EvmFixtures.rlpString(ByteArray(0)),
        EvmFixtures.uint(10143),
        EvmFixtures.rlpString(ByteArray(0)),
        EvmFixtures.rlpString(ByteArray(0)),
    )

    /** An unsigned EIP-155 legacy payload, with optional overrides for single fields. */
    fun legacy(
        fields: List<ByteArray>? = null,
        nonce: ByteArray? = null,
        to: ByteArray? = null,
        value: ByteArray? = null,
        chainId: ByteArray? = null,
        placeholderR: ByteArray? = null,
    ): ByteArray {
        val base = fields ?: legacyFields()
        val items = base.toMutableList()
        nonce?.let { items[0] = it }
        to?.let { items[3] = it }
        value?.let { items[4] = it }
        chainId?.let { items[6] = it }
        placeholderR?.let { items[7] = it }
        return EvmFixtures.rlpList(items)
    }

    fun eip1559Fields(): List<ByteArray> = listOf(
        EvmFixtures.uint(10143),
        EvmFixtures.uint(7),
        EvmFixtures.uint(1_500_000_000L),
        EvmFixtures.uint(30_000_000_000L),
        EvmFixtures.uint(21_000),
        EvmFixtures.rlpString(EvmFixtures.hex(TO)),
        EvmFixtures.uint(1),
        EvmFixtures.rlpString(ByteArray(0)),
        EvmFixtures.rlpList(emptyList()),
    )

    /** A typed EIP-1559 payload: 0x02 followed by the RLP field list. */
    fun eip1559Payload(fields: List<ByteArray> = eip1559Fields()): ByteArray =
        byteArrayOf(0x02) + EvmFixtures.rlpList(fields)

    fun legacyPayload(): ByteArray = legacy()
}
