package app.lensly.core.algorand

import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

/**
 * Algorand account keys as the Ledger Algorand app derives them, so a Lensly phrase restored on a Ledger gives the same
 * addresses. The account path is `m/44'/283'/N'/0/0`.
 *
 * The scheme is Ledger's BOLOS `HDW_NORMAL` Ed25519 derivation (BIP32-Ed25519, Khovratovich child step with g = 32). It
 * is not SLIP-0010 and not ARC-52 xHD, which give other keys for the same phrase. The sources it follows:
 *  - LedgerHQ/speculos, GNU LGPL-3.0, master 679182f8: `src/bolos/os_bip32.c` (`expand_seed_ed25519_bip32`,
 *    `hdw_bip32_ed25519`) and `src/bolos/cx_ec.c` (`sys_cx_eddsa_get_public_key`, `sys_cx_eddsa_sign`). Only the
 *    algorithm is followed; no code is copied. The tests use Speculos's default seed.
 *  - LedgerHQ/app-algorand, MIT per repository metadata, develop 62adedc7: `app/src/coin.h` and `apdu_handler.c` (the
 *    path) and `tests/test_pubkey_cmd.py` (the account 123 vector). `app/src/crypto.c` carries a Zondax, Apache-2.0
 *    header; which licence applies is open for WI-17.
 *
 * The device's public key is the RFC 8032 public key of the 32-byte scalar kL, and the device signs with that same
 * kL. The non-hardened child step needs the point kL*G with kL taken as a raw scalar. BouncyCastle 1.86 has no public
 * raw scalar multiplication, so [scalarMultBaseEncoded] does it with BigInteger arithmetic. That arithmetic is not
 * constant time.
 */
object LedgerEd25519 {
    private const val SEED_BYTES = 64
    private const val HARDENED = Int.MIN_VALUE // 0x80000000 as an Int
    private const val HMAC_SHA256 = "HmacSHA256"
    private const val HMAC_SHA512 = "HmacSHA512"
    private val ED25519_SEED = "ed25519 seed".toByteArray(Charsets.US_ASCII)
    private val TWO_256: BigInteger = BigInteger.ONE.shiftLeft(256)

    // Edwards25519 in extended coordinates: (X, Y, Z, T) with x = X/Z, y = Y/Z and T = X*Y/Z.
    private val FIELD: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    private val CURVE_D: BigInteger =
        BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(FIELD)).mod(FIELD)
    private val BASE: Point = basePoint()
    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    /**
     * An account key. [publicKey] is the 32-byte Ed25519 key the device shows, and [AlgoAddress.encode] turns it into
     * the address. [kL] is the 32-byte seed the device signs with. [kR] is the other half of the extended key,
     * which only child derivation uses. [kL] and [kR] are secret; the caller calls [wipe] when it is done.
     */
    class Key internal constructor(val publicKey: ByteArray, val kL: ByteArray, val kR: ByteArray) {
        /** Zeroes [kL] and [kR]. The public key is not secret and is left as it is. */
        fun wipe() {
            kL.fill(0)
            kR.fill(0)
        }
    }

    /**
     * The key of Algorand account [account] (path `m/44'/283'/account'/0/0`) under the 64-byte BIP-39 [seed].
     * The seed is read and not kept. The caller wipes the returned [Key].
     */
    fun algorandAccount(seed: ByteArray, account: Int): Key {
        require(seed.size == SEED_BYTES) { "BIP-39 seed must be $SEED_BYTES bytes" }
        require(account >= 0) { "account must be 0 or more" }
        val path = intArrayOf(hardened(44), hardened(283), hardened(account), 0, 0)
        val (extended, chain) = derive(seed, path)
        chain.fill(0)
        val kL = extended.copyOfRange(0, 32)
        val kR = extended.copyOfRange(32, 64)
        extended.fill(0)
        return Key(publicKey(kL), kL, kR)
    }

    /** The RFC 8032 public key of the seed [kL]: clamp(SHA-512(kL)[0..32]) times the base point. */
    private fun publicKey(kL: ByteArray): ByteArray = Ed25519PrivateKeyParameters(kL, 0).generatePublicKey().encoded

    private fun hardened(index: Int): Int = index or HARDENED

    /** The extended key kL||kR and chain code at the end of [path]: BOLOS expand, then one step per index. */
    private fun derive(seed: ByteArray, path: IntArray): Pair<ByteArray, ByteArray> {
        var extended = hmac(HMAC_SHA512, ED25519_SEED, seed)
        while ((extended[31].toInt() and 0x20) != 0) {
            val next = hmac(HMAC_SHA512, ED25519_SEED, extended)
            extended.fill(0)
            extended = next
        }
        extended[0] = (extended[0].toInt() and 0xF8).toByte()
        extended[31] = ((extended[31].toInt() and 0x7F) or 0x40).toByte()
        var chain = hmac(HMAC_SHA256, ED25519_SEED, byteArrayOf(0x01) + seed)
        for (index in path) {
            val (nextExtended, nextChain) = childStep(extended, chain, index)
            extended.fill(0)
            chain.fill(0)
            extended = nextExtended
            chain = nextChain
        }
        return extended to chain
    }

    /** One child step of BOLOS `hdw_bip32_ed25519`: the child kL||kR and chain code for [index]. */
    private fun childStep(extended: ByteArray, chain: ByteArray, index: Int): Pair<ByteArray, ByteArray> {
        val kL = extended.copyOfRange(0, 32)
        val kR = extended.copyOfRange(32, 64)
        val indexBytes = littleEndian(index)
        val isHardened = index < 0
        // The parent's point A = kL*G. Only non-hardened steps use it, in both the Z input and the chain-code input.
        val point = if (isHardened) ByteArray(0) else scalarMultBaseEncoded(kL)
        val zInput =
            if (isHardened) byteArrayOf(0x00) + kL + kR + indexBytes else byteArrayOf(0x02) + point + indexBytes
        val z = hmac(HMAC_SHA512, chain, zInput)
        // Z_L keeps 28 bytes: its top 32 bits (bytes 28..31, little-endian) are cleared.
        // Then kL' = kL + 8 * Z_L (mod 2^256).
        val zL = z.copyOfRange(0, 32)
        zL.fill(0, 28, 32)
        val zR = z.copyOfRange(32, 64)
        val childKL = leBytes(fromLittleEndian(kL).add(fromLittleEndian(zL).shiftLeft(3)))
        val childKR = leBytes(fromLittleEndian(kR).add(fromLittleEndian(zR)))
        // The chain code uses the parent's kL||kR when hardened, and the parent's point A otherwise.
        val chainInput =
            if (isHardened) byteArrayOf(0x01) + kL + kR + indexBytes else byteArrayOf(0x03) + point + indexBytes
        val childChain = hmac(HMAC_SHA512, chain, chainInput).copyOfRange(32, 64)
        zInput.fill(0)
        chainInput.fill(0)
        z.fill(0)
        zL.fill(0)
        zR.fill(0)
        kL.fill(0)
        kR.fill(0)
        return (childKL + childKR) to childChain
    }

    /**
     * [scalar] times the base point, encoded per RFC 8032. [scalar] is 32 little-endian bytes, read as the integer
     * they encode, with no clamping and no reduction. Internal, so the tests can check the signing expansion against
     * the same arithmetic.
     */
    internal fun scalarMultBaseEncoded(scalar: ByteArray): ByteArray {
        require(scalar.size == 32) { "scalar must be 32 bytes" }
        return encode(scalarMult(BASE, fromLittleEndian(scalar)))
    }

    private fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data)
    }

    private fun littleEndian(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun fromLittleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    /** The 32-byte little-endian form of [value] mod 2^256. */
    private fun leBytes(value: BigInteger): ByteArray {
        val be = value.mod(TWO_256).toByteArray()
        return ByteArray(32) { i ->
            val at = be.size - 1 - i
            if (at >= 0) be[at] else 0.toByte()
        }
    }

    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private fun basePoint(): Point {
        // The standard base point has y = 4/5 and an even x.
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(FIELD)).mod(FIELD)
        val x = evenX(y)
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(FIELD))
    }

    /** The even x with x^2 = (y^2 - 1) / (d y^2 + 1), as RFC 8032 section 5.1.3 computes it. */
    private fun evenX(y: BigInteger): BigInteger {
        val y2 = y.multiply(y).mod(FIELD)
        val x2 = y2.subtract(BigInteger.ONE)
            .multiply(CURVE_D.multiply(y2).add(BigInteger.ONE).modInverse(FIELD))
            .mod(FIELD)
        var x = x2.modPow(FIELD.add(BigInteger.valueOf(3)).shiftRight(3), FIELD)
        if (x.multiply(x).subtract(x2).mod(FIELD).signum() != 0) {
            x = x.multiply(BigInteger.valueOf(2).modPow(FIELD.subtract(BigInteger.ONE).shiftRight(2), FIELD)).mod(FIELD)
        }
        return if (x.testBit(0)) FIELD.subtract(x) else x
    }

    /** The unified addition for a = -1 (add-2008-hwcd-3, Hisil et al.). It is complete, so it also doubles. */
    private fun add(p: Point, q: Point): Point {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(FIELD)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(FIELD)
        val c = BigInteger.valueOf(2).multiply(p.t).multiply(q.t).multiply(CURVE_D).mod(FIELD)
        val d = BigInteger.valueOf(2).multiply(p.z).multiply(q.z).mod(FIELD)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return Point(
            e.multiply(f).mod(FIELD),
            g.multiply(h).mod(FIELD),
            f.multiply(g).mod(FIELD),
            e.multiply(h).mod(FIELD),
        )
    }

    /** [scalar] times [base] by a Montgomery ladder over 256 bits: each bit costs one addition and one doubling. */
    private fun scalarMult(base: Point, scalar: BigInteger): Point {
        var r0 = IDENTITY
        var r1 = base
        for (i in 255 downTo 0) {
            if (scalar.testBit(i)) {
                r0 = add(r0, r1)
                r1 = add(r1, r1)
            } else {
                r1 = add(r0, r1)
                r0 = add(r0, r0)
            }
        }
        return r0
    }

    /** RFC 8032 point encoding: y little-endian, with the low bit of x in the top bit of the last byte. */
    private fun encode(p: Point): ByteArray {
        val zInv = p.z.modInverse(FIELD)
        val x = p.x.multiply(zInv).mod(FIELD)
        val y = p.y.multiply(zInv).mod(FIELD)
        val out = leBytes(y)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }
}
