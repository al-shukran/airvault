package app.lensly.core.wallet

import java.nio.ByteBuffer
import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

/** Public account data for m/44'/60'/0', enough to derive every receiving address without the seed. */
class AccountXpub(
    /** 33-byte compressed secp256k1 public key of m/44'/60'/0'. */
    val publicKey: ByteArray,
    /** 32-byte BIP32 chain code of m/44'/60'/0'. */
    val chainCode: ByteArray,
    /** Fingerprint of the parent key m/44'/60'. */
    val parentFingerprint: Int,
)

/**
 * Ethereum BIP32 wallet on m/44'/60'/0'/0/i, built from a BIP39 seed (16 to 64 bytes).
 *
 * The derived key objects held here cannot be wiped. The only key material returned as a byte array
 * is [privateKey], and the caller must zero it.
 */
class HdWallet(seed: ByteArray) {
    init {
        require(seed.size in MIN_SEED_BYTES..MAX_SEED_BYTES) { "BIP32 seed must be 16 to 64 bytes" }
    }

    private val master: Bip32ECKeyPair = Bip32ECKeyPair.generateKeyPair(seed)
    private val coinNode: Bip32ECKeyPair = Bip32ECKeyPair.deriveKeyPair(master, intArrayOf(hardened(44), hardened(60)))
    private val account: Bip32ECKeyPair = Bip32ECKeyPair.deriveKeyPair(coinNode, intArrayOf(hardened(0)))

    /** First 4 bytes of HASH160 of the master compressed public key, as a big-endian Int. */
    val masterFingerprint: Int = fingerprint(master)

    /** [masterFingerprint] as 8 lowercase hex characters. */
    val masterFingerprintHex: String = "%08x".format(masterFingerprint)

    fun accountXpub(): AccountXpub = AccountXpub(
        publicKey = compressedPublicKey(account),
        chainCode = account.chainCode,
        parentFingerprint = fingerprint(coinNode),
    )

    /** EIP-55 checksummed address of m/44'/60'/0'/0/[index]. */
    fun address(index: Int): String = addressOf(externalKey(index))

    /** 32-byte private key of m/44'/60'/0'/0/[index]. The caller must zero the returned array. */
    fun privateKey(index: Int): ByteArray = Numeric.toBytesPadded(externalKey(index).privateKey, PRIVATE_KEY_BYTES)

    private fun externalKey(index: Int): Bip32ECKeyPair {
        require(index >= 0) { "index must be a non-hardened child number (0 until 2^31)" }
        return Bip32ECKeyPair.deriveKeyPair(account, intArrayOf(0, index))
    }

    private companion object {
        const val MIN_SEED_BYTES = 16
        const val MAX_SEED_BYTES = 64
        const val PRIVATE_KEY_BYTES = 32

        fun hardened(childNumber: Int): Int = childNumber or Bip32ECKeyPair.HARDENED_BIT

        fun compressedPublicKey(key: Bip32ECKeyPair): ByteArray = key.publicKeyPoint.getEncoded(true)

        fun fingerprint(key: Bip32ECKeyPair): Int =
            ByteBuffer.wrap(Hash.sha256hash160(compressedPublicKey(key))).int

        fun addressOf(key: ECKeyPair): String {
            val publicKey64 = Numeric.toBytesPadded(key.publicKey, 64)
            val addressBytes = Hash.sha3(publicKey64).copyOfRange(12, 32)
            return Keys.toChecksumAddress(addressBytes.toHex())
        }
    }
}
