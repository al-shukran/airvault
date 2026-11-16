package app.lensly.core.wallet

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

/**
 * SLIP-0010 Ed25519 keys: the master key from a BIP39 seed, and hardened children only. Ledger Live and Phantom derive Solana
 * accounts this way, as `m/44'/501'/N'`. SLIP-0010 defines no non-hardened child for Ed25519, so [child] refuses one.
 *
 * The arrays this object creates are zeroed before it returns. The copies it hands to the caller are the caller's to zero.
 */
object Slip10Ed25519 {
    /** The hardened bit of a BIP32 child number. A child number with this bit set is hardened: 44' is `hardened(44)`. */
    const val HARDENED: Int = Int.MIN_VALUE

    private const val MIN_SEED_BYTES = 16
    private const val MAX_SEED_BYTES = 64
    private const val KEY_BYTES = 32

    private val MASTER_KEY = "ed25519 seed".toByteArray(Charsets.US_ASCII)

    /** The child number of hardened index [index], which is 0 until 2^31. */
    fun hardened(index: Int): Int {
        require(index >= 0) { "index must be 0 until 2^31" }
        return index or HARDENED
    }

    /** The master node of [seed], which is 16 to 64 bytes. */
    fun master(seed: ByteArray): Slip10Node {
        require(seed.size in MIN_SEED_BYTES..MAX_SEED_BYTES) { "SLIP-0010 seed must be 16 to 64 bytes" }
        return node(hmacSha512(MASTER_KEY, seed))
    }

    /**
     * The hardened child of [parent] at [childNumber], which must have the hardened bit set. [parent] is not changed; the caller
     * wipes it when it is done with it.
     */
    fun child(parent: Slip10Node, childNumber: Int): Slip10Node {
        require((childNumber and HARDENED) != 0) { "SLIP-0010 Ed25519 has hardened children only" }
        val key = parent.privateKey()
        // The data is 0x00 || k_par || ser32(i), as SLIP-0010 defines it for Ed25519.
        val data = ByteArray(1 + KEY_BYTES + 4)
        try {
            key.copyInto(data, destinationOffset = 1)
            ByteBuffer.wrap(data, 1 + KEY_BYTES, 4).putInt(childNumber)
            return node(hmacSha512(parent.chainCode(), data))
        } finally {
            key.fill(0)
            data.fill(0)
        }
    }

    /**
     * The node at [path] under the master of [seed]. Each element of [path] is a child number, as [child] takes it. Every
     * element must be hardened, or [child] throws.
     */
    fun derive(seed: ByteArray, path: IntArray): Slip10Node {
        var current = master(seed)
        for (childNumber in path) {
            val next = try {
                child(current, childNumber)
            } finally {
                current.wipe()
            }
            current = next
        }
        return current
    }

    /** HMAC-SHA512(key, data), 64 bytes. [node] splits the result and zeroes it. */
    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    /** Splits I = I_L || I_R into the private key I_L and the chain code I_R, then zeroes I. */
    private fun node(i: ByteArray): Slip10Node = try {
        Slip10Node(i.copyOfRange(0, KEY_BYTES), i.copyOfRange(KEY_BYTES, 2 * KEY_BYTES))
    } finally {
        i.fill(0)
    }
}

/**
 * A SLIP-0010 Ed25519 node: a 32-byte private key and a 32-byte chain code. [privateKey] and [chainCode] return copies that the
 * caller must zero. [wipe] zeroes the node's own arrays, and a wiped node refuses to give out its key.
 */
class Slip10Node internal constructor(
    private val key: ByteArray,
    private val chain: ByteArray,
) : AutoCloseable {

    private var wiped = false

    /** The 32-byte private key. A copy: the caller must zero it. */
    fun privateKey(): ByteArray {
        check(!wiped) { "Slip10Node has been wiped" }
        return key.copyOf()
    }

    /** The 32-byte chain code. A copy: the caller must zero it. */
    fun chainCode(): ByteArray {
        check(!wiped) { "Slip10Node has been wiped" }
        return chain.copyOf()
    }

    /**
     * The 32-byte raw Ed25519 public key, without the `00` prefix SLIP-0010 writes in front of it. BouncyCastle builds its own
     * copy of the private key to compute this, and that copy cannot be zeroed from here.
     */
    fun publicKey(): ByteArray {
        check(!wiped) { "Slip10Node has been wiped" }
        return Ed25519PrivateKeyParameters(key, 0).generatePublicKey().encoded
    }

    /** Zeroes the key and the chain code. Safe to call more than once. */
    fun wipe() {
        key.fill(0)
        chain.fill(0)
        wiped = true
    }

    override fun close() = wipe()
}
