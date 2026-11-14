package app.lensly.core.evm

import app.lensly.core.ur.DataType
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import org.web3j.crypto.Hash

/**
 * The EIP-155 specification's worked signing example: chain ID 1, nonce 9, 20 gwei, 21000 gas,
 * `0x3535...35`, 10^18 wei, empty data, private key `0x4646...46`.
 */
class Eip155VectorTest {

    private val vector = PublishedSigningVectors.eip155()
    private val unsigned = EvmFixtures.hex(vector.unsigned)
    private val key = EvmFixtures.hex(vector.privateKey)

    @Test
    fun signingHashMatchesPublishedHash() {
        assertEquals(vector.signingHash, "0x" + Hash.sha3(unsigned).toHex())
    }

    @Test
    fun unsignedTransactionDecodesToPublishedFields() {
        val result = assertIs<UnsignedTx.Result.Ok>(UnsignedTx.decode(unsigned, DataType.TRANSACTION))
        val tx = result.tx
        assertEquals(TxType.LEGACY_EIP155, tx.type)
        assertEquals(BigInteger.ONE, tx.chainId)
        assertEquals(BigInteger.valueOf(9), tx.nonce)
        assertEquals(BigInteger.valueOf(20_000_000_000L), tx.gasPrice)
        assertEquals(BigInteger.valueOf(21_000), tx.gasLimit)
        assertEquals("0x3535353535353535353535353535353535353535", tx.to)
        assertEquals(BigInteger.TEN.pow(18), tx.value)
        assertEquals("0x", tx.data)
        assertEquals(null, tx.maxPriorityFeePerGas)
        assertEquals(null, tx.maxFeePerGas)
    }

    @Test
    fun signatureMatchesPublishedSignedTransaction() {
        val signature = assertIs<TxSignResult.Signed>(
            TxSigner.sign(unsigned, DataType.TRANSACTION, key),
        ).signature

        // v = 1 * 2 + 35 + 0 = 37 = 0x25, so the signature is 65 bytes.
        assertEquals(65, signature.size)
        assertEquals(
            "28ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276" +
                "67cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83" +
                "25",
            signature.toHex(),
        )
    }

    @Test
    fun reencodedSignedTransactionMatchesPublishedBytes() {
        val signature = assertIs<TxSignResult.Signed>(
            TxSigner.sign(unsigned, DataType.TRANSACTION, key),
        ).signature
        val unsignedFields = (Rlp.decode(unsigned) as Rlp.Lst).items
        val signed = EvmFixtures.rlpList(
            unsignedFields.take(6).map { EvmFixtures.rlpString((it as Rlp.Str).bytes) } +
                EvmFixtures.rlpString(signature.copyOfRange(64, 65)) +
                EvmFixtures.rlpString(signature.copyOfRange(0, 32)) +
                EvmFixtures.rlpString(signature.copyOfRange(32, 64)),
        )

        // Byte-for-byte equality with the signed transaction printed in EIP-155.
        assertEquals(vector.signed, "0x" + signed.toHex())
    }
}
