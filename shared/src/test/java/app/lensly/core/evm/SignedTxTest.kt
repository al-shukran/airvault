package app.lensly.core.evm

import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import org.web3j.crypto.Keys

/**
 * The signed transaction that [UnsignedTx.encodeSigned] assembles from the published unsigned fields and a real signature must be the
 * published bytes, and the signer recovered from that signature must be the key the vector names.
 */
class SignedTxTest {
    private val vectors = PublishedSigningVectors.eip1559()

    private fun txOf(vector: PublishedSigningVectors.Eip1559) = UnsignedTx(
        type = TxType.EIP1559,
        chainId = BigInteger.valueOf(vector.chainId),
        nonce = BigInteger.valueOf(vector.nonce),
        to = Keys.toChecksumAddress(vector.to),
        value = BigInteger.valueOf(vector.value),
        data = "0x",
        gasLimit = BigInteger.valueOf(vector.gasLimit),
        gasPrice = null,
        maxPriorityFeePerGas = BigInteger.valueOf(vector.maxPriorityFeePerGas),
        maxFeePerGas = BigInteger.valueOf(vector.maxFeePerGas),
    )

    @Test
    fun theSignedTransactionIsThePublishedBytes() {
        for (vector in vectors) {
            val tx = txOf(vector)
            val payload = assertNotNull(tx.encode())
            val signature = TxSigner.sign(payload, tx, EvmFixtures.hex(vector.privateKey))

            assertEquals(vector.signedTx, "0x" + assertNotNull(tx.encodeSigned(signature)).toHex(), "nonce ${vector.nonce}")
        }
    }

    @Test
    fun theRecoveredSignerIsTheKeyThatSigned() {
        for (vector in vectors) {
            val tx = txOf(vector)
            val payload = assertNotNull(tx.encode())
            val signature = TxSigner.sign(payload, tx, EvmFixtures.hex(vector.privateKey))

            assertEquals(
                AccountAddresses.addressOfPrivateKey(EvmFixtures.hex(vector.privateKey)),
                TxSigner.recoverSigner(payload, signature),
                "nonce ${vector.nonce}",
            )
        }
    }

    @Test
    fun aSignatureDoesNotRecoverTheSignerOfAnotherPayload() {
        val first = vectors[0]
        val second = vectors[1]
        val firstTx = txOf(first)
        val signature = TxSigner.sign(assertNotNull(firstTx.encode()), firstTx, EvmFixtures.hex(first.privateKey))
        val secondPayload = assertNotNull(txOf(second).encode())

        assertNotEquals(AccountAddresses.addressOfPrivateKey(EvmFixtures.hex(first.privateKey)), TxSigner.recoverSigner(secondPayload, signature))
    }

    @Test
    fun onlyAnEip1559SignatureWithARecoveryIdOfZeroOrOneIsAssembled() {
        val vector = vectors[0]
        val tx = txOf(vector)
        val payload = assertNotNull(tx.encode())
        val signature = TxSigner.sign(payload, tx, EvmFixtures.hex(vector.privateKey))
        val rs = signature.copyOf(64)

        assertNull(tx.encodeSigned(rs))
        assertNull(tx.encodeSigned(rs + byteArrayOf(2)))
        assertNull(tx.encodeSigned(rs + byteArrayOf(27)))
        assertNull(TxSigner.recoverSigner(payload, rs + byteArrayOf(2)))
        assertNull(TxSigner.recoverSigner(payload, rs + byteArrayOf(27)))
    }

    @Test
    fun aLegacyTransactionIsNotAssembledAsEip1559() {
        val legacy = UnsignedTx(
            type = TxType.LEGACY_EIP155,
            chainId = BigInteger.ONE,
            nonce = BigInteger.ZERO,
            to = null,
            value = BigInteger.ZERO,
            data = "0x",
            gasLimit = BigInteger.valueOf(21_000),
            gasPrice = BigInteger.ONE,
            maxPriorityFeePerGas = null,
            maxFeePerGas = null,
        )

        assertNull(legacy.encodeSigned(ByteArray(65)))
    }
}
