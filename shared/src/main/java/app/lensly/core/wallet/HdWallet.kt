package app.lensly.core.wallet

import java.nio.ByteBuffer
import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

/**
 * A public extended key, enough to derive addresses without the seed. It is the account key m/44'/60'/N' of an account, or the address
 * key m/44'/60'/N'/0/0 of that account, which [AccountAddresses.addressXpub] derives from it.
 */
class AccountXpub(
    /** 33-byte compressed secp256k1 public key of the key's path. */
    val publicKey: ByteArray,
    /** 32-byte BIP32 chain code of the key's path. */
    val chainCode: ByteArray,
    /** Fingerprint of the parent key: m/44'/60' for an account key, m/44'/60'/N'/0 for an address key. */
    val parentFingerprint: Int,
) {
    /** Two keys are equal when they hold the same public key, chain code and parent fingerprint. */
    override fun equals(other: Any?): Boolean = other is AccountXpub &&
        publicKey.contentEquals(other.publicKey) && chainCode.contentEquals(other.chainCode) && parentFingerprint == other.parentFingerprint

    override fun hashCode(): Int = (publicKey.contentHashCode() * 31 + chainCode.contentHashCode()) * 31 + parentFingerprint
}

/**
 * Ethereum BIP32 wallet on the Ledger Live paths, built from a BIP39 seed (16 to 64 bytes). Account N is m/44'/60'/N', and its one
 * address is m/44'/60'/N'/0/0, the address that Ledger Live shows for account N. A seed phrase restored on a Ledger gives the same
 * addresses.
 *
 * The derived key objects held here cannot be wiped. The only key material returned as a byte array is [privateKey], and the caller
 * must zero it.
 */
class HdWallet(seed: ByteArray) {
    init {
        require(seed.size in MIN_SEED_BYTES..MAX_SEED_BYTES) { "BIP32 seed must be 16 to 64 bytes" }
    }

    private val master: Bip32ECKeyPair = Bip32ECKeyPair.generateKeyPair(seed)
    private val coinNode: Bip32ECKeyPair = Bip32ECKeyPair.deriveKeyPair(master, intArrayOf(hardened(44), hardened(60)))

    /** First 4 bytes of HASH160 of the master compressed public key, as a big-endian Int. */
    val masterFingerprint: Int = fingerprint(master)

    /** [masterFingerprint] as 8 lowercase hex characters. */
    val masterFingerprintHex: String = "%08x".format(masterFingerprint)

    /** The public account key of account [account], m/44'/60'/[account]' (depth 3). Public data only. Account 0 is the default. */
    fun accountXpub(account: Int = 0): AccountXpub {
        val node = accountNode(account)
        return AccountXpub(compressedPublicKey(node), node.chainCode, fingerprint(coinNode))
    }

    /** EIP-55 checksummed address of m/44'/60'/[account]'/0/[index]. Index 0 is the account's own address. */
    fun address(account: Int, index: Int = 0): String = addressOf(addressNode(account, index))

    /** 32-byte private key of m/44'/60'/[account]'/0/[index]. The caller must zero the returned array. */
    fun privateKey(account: Int, index: Int = 0): ByteArray =
        Numeric.toBytesPadded(addressNode(account, index).privateKey, PRIVATE_KEY_BYTES)

    private fun accountNode(account: Int): Bip32ECKeyPair {
        require(account >= 0) { "account must be a non-hardened account number (0 until 2^31)" }
        return Bip32ECKeyPair.deriveKeyPair(coinNode, intArrayOf(hardened(account)))
    }

    private fun addressNode(account: Int, index: Int): Bip32ECKeyPair {
        require(index >= 0) { "index must be a non-hardened child number (0 until 2^31)" }
        return Bip32ECKeyPair.deriveKeyPair(accountNode(account), intArrayOf(0, index))
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
