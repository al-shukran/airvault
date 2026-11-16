package app.lensly.core.solana

import app.lensly.core.wallet.Slip10Ed25519
import fr.acinq.bitcoin.Base58

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

    /** The base58 address of account [index]. */
    fun address(seed: ByteArray, index: Int): String = Base58.encode(publicKey(seed, index))
}
