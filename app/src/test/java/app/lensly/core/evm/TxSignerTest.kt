package app.lensly.core.evm

import app.lensly.core.ur.DataType
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Hash
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.Sign
import org.web3j.crypto.TransactionEncoder

class TxSignerTest {

    private val to = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
    private val key = EvmFixtures.artKey
    private val accountZero = EvmFixtures.normalize(EvmFixtures.ART_ADDRESS)

    @Test
    fun eip1559SignatureRecoversToAccountZero() {
        val unsigned = TransactionEncoder.encode(eip1559Tx(chainId = 10143L))
        val signature = signed(TxSigner.sign(unsigned, DataType.TYPED_TRANSACTION, key))

        assertEquals(65, signature.size)
        val v = signature[64].toInt()
        assertTrue(v == 0 || v == 1, "v must be a recovery id, was $v")
        assertEquals(accountZero, EvmFixtures.recoveredAddress(Hash.sha3(unsigned), r(signature), s(signature), v))
    }

    @Test
    fun eip1559MatchesWeb3jSigningOfTheSameTransaction() {
        val raw = eip1559Tx(chainId = 10143L)
        val signature = signed(TxSigner.sign(TransactionEncoder.encode(raw), DataType.TYPED_TRANSACTION, key))

        val signedBytes = TransactionEncoder.signMessage(raw, Credentials.create(ECKeyPair.create(key)))
        assertEquals(0x02, signedBytes[0].toInt())
        val fields = (Rlp.decode(signedBytes.copyOfRange(1, signedBytes.size)) as Rlp.Lst).items
        assertEquals(12, fields.size)
        assertEquals(BigInteger.valueOf(signature[64].toLong()), uintOf(fields[9]))
        assertEquals(BigInteger(1, r(signature)), uintOf(fields[10]))
        assertEquals(BigInteger(1, s(signature)), uintOf(fields[11]))
    }

    @Test
    fun legacyEip155SignatureHasMultiByteVAndRecoversToAccountZero() {
        val unsigned = TransactionEncoder.encode(legacyTx(), 10143L)
        val signature = signed(TxSigner.sign(unsigned, DataType.TRANSACTION, key))

        // v = 10143 * 2 + 35 + recId is 20321 or 20322, which needs two bytes.
        assertEquals(66, signature.size)
        val v = BigInteger(1, signature.copyOfRange(64, 66)).toInt()
        assertTrue(v == 20321 || v == 20322, "unexpected v $v")
        assertEquals(accountZero, EvmFixtures.recoveredAddress(Hash.sha3(unsigned), r(signature), s(signature), v - 20321))
    }

    @Test
    fun legacyMatchesWeb3jEip155Signing() {
        val raw = legacyTx()
        val signature = signed(TxSigner.sign(TransactionEncoder.encode(raw, 10143L), DataType.TRANSACTION, key))

        val signedBytes = TransactionEncoder.signMessage(raw, 10143L, Credentials.create(ECKeyPair.create(key)))
        val fields = (Rlp.decode(signedBytes) as Rlp.Lst).items
        assertEquals(9, fields.size)
        assertEquals(BigInteger(1, signature.copyOfRange(64, 66)), uintOf(fields[6]))
        assertEquals(BigInteger(1, r(signature)), uintOf(fields[7]))
        assertEquals(BigInteger(1, s(signature)), uintOf(fields[8]))
    }

    @Test
    fun legacyOnChainOneUsesOneByteV() {
        val raw = RawTransaction.createTransaction(
            BigInteger.ONE,
            BigInteger.valueOf(20_000_000_000L),
            BigInteger.valueOf(21_000),
            to,
            BigInteger.ZERO,
            "0x",
        )
        val signature = signed(TxSigner.sign(TransactionEncoder.encode(raw, 1L), DataType.TRANSACTION, key))

        assertEquals(65, signature.size)
        val v = signature[64].toInt() and 0xFF
        assertTrue(v == 37 || v == 38, "unexpected v $v")
    }

    @Test
    fun signaturesAreLowS() {
        val halfOrder = Sign.CURVE_PARAMS.n.shiftRight(1)
        for (nonce in 0L..4L) {
            val raw = RawTransaction.createTransaction(
                10143L,
                BigInteger.valueOf(nonce),
                BigInteger.valueOf(21_000),
                to,
                BigInteger.ONE,
                "0x",
                BigInteger.ONE,
                BigInteger.valueOf(30_000_000_000L),
            )
            val signature = signed(TxSigner.sign(TransactionEncoder.encode(raw), DataType.TYPED_TRANSACTION, key))
            assertTrue(BigInteger(1, s(signature)) <= halfOrder, "s is not low for nonce $nonce")
        }
    }

    @Test
    fun refusesOtherDataTypesAndMalformedPayloads() {
        val legacy = TransactionEncoder.encode(legacyTx(), 10143L)
        assertIs<TxSigner.Result.Refused>(TxSigner.sign(legacy, DataType.PERSONAL_MESSAGE, key))
        assertIs<TxSigner.Result.Refused>(TxSigner.sign(legacy, DataType.TYPED_DATA, key))

        val eip2930 = byteArrayOf(0x01) + TxSamples.eip1559Payload().copyOfRange(1, TxSamples.eip1559Payload().size)
        assertIs<TxSigner.Result.Refused>(TxSigner.sign(eip2930, DataType.TYPED_TRANSACTION, key))

        // Six fields is a legacy transaction without the EIP-155 chainId placeholders.
        val withoutEip155 = TxSamples.legacy(fields = TxSamples.legacyFields().take(6))
        assertIs<TxSigner.Result.Refused>(TxSigner.sign(withoutEip155, DataType.TRANSACTION, key))
    }

    @Test
    fun rejectsKeysOfTheWrongLength() {
        val unsigned = TransactionEncoder.encode(legacyTx(), 10143L)
        assertFailsWith<IllegalArgumentException> { TxSigner.sign(unsigned, DataType.TRANSACTION, ByteArray(31)) }
    }

    private fun eip1559Tx(chainId: Long): RawTransaction = RawTransaction.createTransaction(
        chainId,
        BigInteger.valueOf(7),
        BigInteger.valueOf(21_000),
        to,
        BigInteger("1000000000000000000"),
        "0x",
        BigInteger.valueOf(1_500_000_000L),
        BigInteger.valueOf(30_000_000_000L),
    )

    private fun legacyTx(): RawTransaction = RawTransaction.createTransaction(
        BigInteger.valueOf(3),
        BigInteger.valueOf(20_000_000_000L),
        BigInteger.valueOf(60_000),
        to,
        BigInteger.ZERO,
        "0x",
    )

    private fun signed(result: TxSigner.Result): ByteArray = assertIs<TxSigner.Result.Signed>(result).signature

    private fun r(signature: ByteArray): ByteArray = signature.copyOfRange(0, 32)

    private fun s(signature: ByteArray): ByteArray = signature.copyOfRange(32, 64)

    private fun uintOf(item: Rlp): BigInteger = BigInteger(1, (item as Rlp.Str).bytes)
}
