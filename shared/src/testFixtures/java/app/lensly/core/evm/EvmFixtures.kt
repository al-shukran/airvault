package app.lensly.core.evm

import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import java.math.BigInteger
import org.web3j.crypto.ECDSASignature
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign

/** Shared test data: the BIP39 "abandon ... art" account and small RLP and recovery helpers. */
object EvmFixtures {
    val artMnemonic: List<String> = List(23) { "abandon" } + "art"

    /** m/44'/60'/0'/0/0 for the "abandon ... art" mnemonic. The viem reference vector gives this key and address. */
    val artKey: ByteArray by lazy { HdWallet(Mnemonic.toSeed(artMnemonic)).privateKey(0) }
    const val ART_ADDRESS = "0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb"

    /** Address recovered from r || s || v over [hash], normalized to lowercase hex without a prefix. */
    fun recoveredAddress(hash: ByteArray, r: ByteArray, s: ByteArray, recId: Int): String {
        val publicKey = Sign.recoverFromSignature(
            recId,
            ECDSASignature(BigInteger(1, r), BigInteger(1, s)),
            hash,
        )
        requireNotNull(publicKey) { "signature does not recover a key" }
        return normalize(Keys.getAddress(publicKey))
    }

    fun normalize(address: String): String = address.lowercase().removePrefix("0x")

    fun rlpString(bytes: ByteArray): ByteArray = when {
        bytes.size == 1 && (bytes[0].toInt() and 0xFF) < 0x80 -> bytes
        bytes.size <= 55 -> byteArrayOf((0x80 + bytes.size).toByte()) + bytes
        else -> lengthPrefix(0xB7, bytes.size) + bytes
    }

    fun rlpList(items: List<ByteArray>): ByteArray {
        val payload = items.fold(ByteArray(0)) { acc, item -> acc + item }
        return when {
            payload.size <= 55 -> byteArrayOf((0xC0 + payload.size).toByte()) + payload
            else -> lengthPrefix(0xF7, payload.size) + payload
        }
    }

    fun hex(value: String): ByteArray = org.web3j.utils.Numeric.hexStringToByteArray(value)

    /** Minimal big-endian bytes of a non-negative integer, with zero as the empty string. */
    fun uint(value: Long): ByteArray = rlpString(BigInteger.valueOf(value).toMinimalBytes())

    private fun lengthPrefix(offset: Int, length: Int): ByteArray {
        val lengthBytes = BigInteger.valueOf(length.toLong()).toMinimalBytes()
        return byteArrayOf((offset + lengthBytes.size).toByte()) + lengthBytes
    }

    private fun BigInteger.toMinimalBytes(): ByteArray {
        if (signum() == 0) return ByteArray(0)
        val bytes = toByteArray()
        return if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }
}
