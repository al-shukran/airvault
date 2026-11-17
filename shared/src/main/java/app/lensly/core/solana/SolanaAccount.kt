package app.lensly.core.solana

import app.lensly.core.wallet.Slip10Ed25519
import app.lensly.core.wallet.Slip10Node
import fr.acinq.bitcoin.Base58
import java.security.MessageDigest

/**
 * Solana accounts as Ledger Live derives them. Account N is `m/44'/501'/N'`: an Ed25519 key from the BIP39 seed, by SLIP-0010
 * ([Slip10Ed25519]). Its address is the base58 of the 32-byte public key, with no checksum.
 *
 * Each call derives the private key from [seed] and zeroes it before it returns. Nothing here keeps a private key.
 */
object SolanaAccount {

    /** The 32-byte Ed25519 public key of account [index]. [seed] is the 64-byte BIP39 seed, and the caller keeps it. */
    fun publicKey(seed: ByteArray, index: Int): ByteArray {
        val path = intArrayOf(Slip10Ed25519.hardened(44), Slip10Ed25519.hardened(501), Slip10Ed25519.hardened(index))
        return Slip10Ed25519.derive(seed, path).use { it.publicKey() }
    }

    /** The 32-byte SLIP-0010 chain code of account [index]. It is public, and a Solana pairing key carries it. [seed] is the caller's. */
    fun chainCode(seed: ByteArray, index: Int): ByteArray {
        val path = intArrayOf(Slip10Ed25519.hardened(44), Slip10Ed25519.hardened(501), Slip10Ed25519.hardened(index))
        return Slip10Ed25519.derive(seed, path).use { it.chainCode() }
    }

    /**
     * The SLIP-0010 node of account [index], with its private key and chain code. The caller closes it. [seed] is the caller's, and the
     * caller zeroes it.
     */
    fun node(seed: ByteArray, index: Int): Slip10Node =
        Slip10Ed25519.derive(seed, intArrayOf(Slip10Ed25519.hardened(44), Slip10Ed25519.hardened(501), Slip10Ed25519.hardened(index)))

    /** The base58 address of account [index]. */
    fun address(seed: ByteArray, index: Int): String = addressOf(publicKey(seed, index))

    /** The base58 address of a 32-byte public key, which is the address itself with no checksum. */
    fun addressOf(publicKey: ByteArray): String = Base58.encode(publicKey)

    /**
     * The 32-byte address that Solana's `Pubkey::create_with_seed` gives: the SHA-256 of [base], then [seed], then [owner]. [base] and
     * [owner] are 32 bytes, and the caller checks that [seed] is at most 32 bytes. A nonce account made with a seed lives at this address.
     */
    fun createWithSeed(base: ByteArray, seed: ByteArray, owner: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(base)
        digest.update(seed)
        digest.update(owner)
        return digest.digest()
    }
}
