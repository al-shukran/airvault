package app.lensly.core.evm

import app.lensly.core.ur.DataType
import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test
import org.web3j.crypto.AccessListObject
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric

private const val CHAIN = 1L
private const val RECIPIENT = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"

class UnsignedTxPayloadTest {

    private fun privateKey(): ByteArray = Numeric.hexStringToByteArray("0x" + "11".repeat(32))

    private fun decoded(signData: ByteArray, dataType: DataType): UnsignedTx =
        assertIs<UnsignedTx.Result.Ok>(UnsignedTx.decode(signData, dataType)).tx

    private fun legacy(data: String): ByteArray = TransactionEncoder.encode(
        RawTransaction.createTransaction(
            BigInteger.valueOf(3),
            BigInteger.valueOf(20_000_000_000L),
            BigInteger.valueOf(60_000),
            RECIPIENT,
            BigInteger.ONE,
            data,
        ),
        CHAIN,
    )

    private fun eip1559(data: String): ByteArray = TransactionEncoder.encode(
        RawTransaction.createTransaction(
            CHAIN,
            BigInteger.valueOf(7),
            BigInteger.valueOf(21_000),
            RECIPIENT,
            BigInteger.ONE,
            data,
            BigInteger.valueOf(1_500_000_000L),
            BigInteger.valueOf(30_000_000_000L),
        ),
    )

    @Test
    fun aLegacyTransactionRebuildsItsOwnPayload() {
        val signData = legacy("0x")

        assertContentEquals(signData, decoded(signData, DataType.TRANSACTION).encode())
    }

    @Test
    fun anEip1559TransactionWithTheLongestCalldataRebuildsItsOwnPayload() {
        // 1 KiB of calldata, the longest the decoder accepts. The RLP lengths are long-form here.
        val signData = eip1559("0x" + "ab".repeat(1024))

        assertContentEquals(signData, decoded(signData, DataType.TYPED_TRANSACTION).encode())
    }

    @Test
    fun aFieldOutsideItsCanonicalFormHasNoPayload() {
        val tx = decoded(legacy("0xabcd"), DataType.TRANSACTION)

        assertNull(tx.copy(to = tx.to?.lowercase()).encode(), "a to that is not its checksum")
        assertNull(tx.copy(data = "0xABCD").encode(), "calldata that is not lowercase hex")
        assertNull(tx.copy(maxFeePerGas = BigInteger.ONE).encode(), "a legacy transaction with an EIP-1559 fee")
    }

    private val storageKey = "0x" + "22".repeat(32)
    private val other = "0x1111111111111111111111111111111111111111"

    /** An EIP-1559 transaction with [accessList], as web3j encodes it. */
    private fun eip1559WithAccessList(accessList: List<AccessListObject>): ByteArray = TransactionEncoder.encode(
        RawTransaction.createTransaction(
            CHAIN,
            BigInteger.valueOf(7),
            BigInteger.valueOf(21_000),
            RECIPIENT,
            BigInteger.ONE,
            "0x",
            BigInteger.valueOf(1_500_000_000L),
            BigInteger.valueOf(30_000_000_000L),
            accessList,
        ),
    )

    /** An EIP-2930 transaction with [accessList], as web3j encodes it. */
    private fun eip2930WithAccessList(accessList: List<AccessListObject>): ByteArray = TransactionEncoder.encode(
        RawTransaction.createTransaction(
            CHAIN,
            BigInteger.valueOf(7),
            BigInteger.valueOf(20_000_000_000L),
            BigInteger.valueOf(21_000),
            RECIPIENT,
            BigInteger.ONE,
            "0x",
            accessList,
        ),
    )

    @Test
    fun anEmptyAccessListRebuildsItsPayload() {
        val signData = eip1559WithAccessList(emptyList())
        val tx = decoded(signData, DataType.TYPED_TRANSACTION)

        assertEquals(emptyList(), tx.accessList)
        assertContentEquals(signData, tx.encode())
    }

    @Test
    fun aNonEmptyEip1559AccessListRebuildsItsPayload() {
        val signData = eip1559WithAccessList(
            listOf(AccessListObject(RECIPIENT, listOf(storageKey, "0x" + "33".repeat(32))), AccessListObject(other, emptyList())),
        )
        val tx = decoded(signData, DataType.TYPED_TRANSACTION)

        val expected = listOf(AccessListEntry(RECIPIENT.lowercase(), listOf(storageKey, "0x" + "33".repeat(32))), AccessListEntry(other, emptyList()))
        assertEquals(expected, tx.accessList)
        assertContentEquals(signData, tx.encode())
    }

    @Test
    fun aNonEmptyEip2930AccessListRebuildsItsPayloadWithItsGasPrice() {
        val signData = eip2930WithAccessList(listOf(AccessListObject(RECIPIENT, listOf(storageKey))))
        val tx = decoded(signData, DataType.TYPED_TRANSACTION)

        assertEquals(TxType.EIP2930, tx.type)
        assertEquals(BigInteger.valueOf(20_000_000_000L), tx.gasPrice)
        assertEquals(BigInteger.valueOf(21_000), tx.gasLimit)
        assertContentEquals(signData, tx.encode())
    }

    @Test
    fun aTamperedAccessListHasNoPayloadThatMatchesTheSignedBytes() {
        val signData = eip1559WithAccessList(listOf(AccessListObject(RECIPIENT, listOf(storageKey))))
        val tx = decoded(signData, DataType.TYPED_TRANSACTION)

        assertFalse(signData.contentEquals(tx.copy(accessList = emptyList()).encode()))
        assertFalse(signData.contentEquals(tx.copy(accessList = listOf(AccessListEntry(other, listOf(storageKey)))).encode()))
    }

    @Test
    fun anAccessListAddressThatIsNotTwentyBytesIsRejected() {
        val signData = eip1559WithAccessList(listOf(AccessListObject("0x" + "11".repeat(19), emptyList())))

        assertIs<UnsignedTx.Result.Rejected>(UnsignedTx.decode(signData, DataType.TYPED_TRANSACTION))
    }

    @Test
    fun aSignatureIsNeverMadeOverBytesOtherThanTheTransactionPayload() {
        val tx = decoded(legacy("0x"), DataType.TRANSACTION)

        assertFailsWith<IllegalArgumentException> { TxSigner.sign(legacy("0x12"), tx, privateKey()) }
        assertEquals(65, TxSigner.sign(legacy("0x"), tx, privateKey()).size)
    }
}
