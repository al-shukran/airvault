package app.lensly.core.evm

import app.lensly.core.ur.DataType
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import org.web3j.crypto.Keys

/**
 * EIP-1559 signing vectors from ethereumjs-monorepo `packages/tx/test/testData/eip1559.ts`.
 * Each published entry pairs a private key with the expected signed transaction, so the
 * signature is reproduced exactly, not merely recovered.
 *
 * Chain ID 4 in every vector. The signed transaction is the published one minus the outer RLP
 * string wrapper that ethereumjs uses for block bodies; the fixture holds the `0x02 || rlp([...])`
 * bytes.
 */
class Eip1559VectorTest {

    private val vectors = PublishedSigningVectors.eip1559()

    @Test
    fun publishedTransactionsDecodeToPublishedFields() {
        assertEquals(3, vectors.size)
        for (vector in vectors) {
            val fields = signedFields(vector)
            assertEquals(BigInteger.valueOf(vector.chainId), uint(fields[0]), "chainId of nonce ${vector.nonce}")
            assertEquals(BigInteger.valueOf(vector.nonce), uint(fields[1]), "nonce")
            assertEquals(BigInteger.valueOf(vector.maxPriorityFeePerGas), uint(fields[2]), "priority fee")
            assertEquals(BigInteger.valueOf(vector.maxFeePerGas), uint(fields[3]), "max fee")
            assertEquals(BigInteger.valueOf(vector.gasLimit), uint(fields[4]), "gas limit")
            assertEquals(vector.to.substring(2).lowercase(), (fields[5] as Rlp.Str).bytes.toHex(), "to")
            assertEquals(BigInteger.valueOf(vector.value), uint(fields[6]), "value")
            assertEquals(0, (fields[7] as Rlp.Str).bytes.size, "data")
            assertEquals(0, (fields[8] as Rlp.Lst).items.size, "access list")
        }
    }

    @Test
    fun unsignedPayloadDecodesToPublishedFields() {
        for (vector in vectors) {
            val result = assertIs<UnsignedTx.Result.Ok>(UnsignedTx.decode(unsigned(vector), DataType.TYPED_TRANSACTION))
            val tx = result.tx
            assertEquals(TxType.EIP1559, tx.type)
            assertEquals(BigInteger.valueOf(vector.chainId), tx.chainId)
            assertEquals(BigInteger.valueOf(vector.nonce), tx.nonce)
            assertEquals(BigInteger.valueOf(vector.maxPriorityFeePerGas), tx.maxPriorityFeePerGas, "nonce ${vector.nonce}")
            assertEquals(BigInteger.valueOf(vector.maxFeePerGas), tx.maxFeePerGas, "nonce ${vector.nonce}")
            assertEquals(BigInteger.valueOf(vector.gasLimit), tx.gasLimit, "nonce ${vector.nonce}")
            assertEquals(BigInteger.valueOf(vector.value), tx.value, "nonce ${vector.nonce}")
            assertEquals("0x", tx.data, "nonce ${vector.nonce}")
            assertEquals(null, tx.gasPrice)
            assertEquals(Keys.toChecksumAddress(vector.to), tx.to)
        }
    }

    @Test
    fun signatureMatchesPublishedSignedTransaction() {
        for (vector in vectors) {
            val signature = assertIs<TxSigner.Result.Signed>(
                TxSigner.sign(unsigned(vector), DataType.TYPED_TRANSACTION, EvmFixtures.hex(vector.privateKey)),
            ).signature
            val fields = signedFields(vector)

            assertEquals(65, signature.size, "nonce ${vector.nonce}")
            assertEquals(uint(fields[9]).toInt(), signature[64].toInt(), "yParity of nonce ${vector.nonce}")
            assertEquals(
                (fields[10] as Rlp.Str).bytes.toHex(),
                signature.copyOfRange(0, 32).toHex(),
                "r of nonce ${vector.nonce}",
            )
            assertEquals(
                (fields[11] as Rlp.Str).bytes.toHex(),
                signature.copyOfRange(32, 64).toHex(),
                "s of nonce ${vector.nonce}",
            )
        }
    }

    @Test
    fun reencodedSignedTransactionMatchesPublishedBytes() {
        for (vector in vectors) {
            val signature = assertIs<TxSigner.Result.Signed>(
                TxSigner.sign(unsigned(vector), DataType.TYPED_TRANSACTION, EvmFixtures.hex(vector.privateKey)),
            ).signature
            val signed = EvmFixtures.rlpList(
                signedFields(vector).take(8).map { EvmFixtures.rlpString((it as Rlp.Str).bytes) } +
                    EvmFixtures.rlpList(emptyList()) +
                    EvmFixtures.rlpString(minimalBytes(signature[64])) +
                    EvmFixtures.rlpString(signature.copyOfRange(0, 32)) +
                    EvmFixtures.rlpString(signature.copyOfRange(32, 64)),
            )
            assertEquals(vector.signedTx, "0x02" + signed.toHex(), "transaction with nonce ${vector.nonce}")
        }
    }

    private fun signedFields(vector: PublishedSigningVectors.Eip1559): List<Rlp> {
        val bytes = EvmFixtures.hex(vector.signedTx)
        assertEquals(0x02, bytes[0].toInt() and 0xFF, "type byte")
        val fields = (Rlp.decode(bytes.copyOfRange(1, bytes.size)) as Rlp.Lst).items
        assertEquals(12, fields.size, "signed EIP-1559 fields")
        return fields
    }

    /** The unsigned payload the wallet sends: 0x02 followed by the first nine published fields. */
    private fun unsigned(vector: PublishedSigningVectors.Eip1559): ByteArray {
        val fields = signedFields(vector).take(8).map { EvmFixtures.rlpString((it as Rlp.Str).bytes) }
        return byteArrayOf(0x02) + EvmFixtures.rlpList(fields + EvmFixtures.rlpList(emptyList()))
    }

    private fun uint(item: Rlp): BigInteger = BigInteger(1, (item as Rlp.Str).bytes)

    /** RLP integer encoding of a value that fits in one byte, with zero as the empty string. */
    private fun minimalBytes(value: Byte): ByteArray = if (value == 0.toByte()) ByteArray(0) else byteArrayOf(value)
}
