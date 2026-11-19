package app.lensly.core.algorand

import app.airvault.chains.algorand.AlgoAddress
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.math.ec.rfc7748.X25519Field

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
 * raw-scalar base multiplication (its Ed25519 routine is reachable only through an internal token), so
 * [scalarMultBaseEncoded] does it here, over BouncyCastle's X25519Field arithmetic, in constant time:
 *  - The work is the same for every scalar: 64 four-bit windows, most significant first, each four doublings and one
 *    addition.
 *  - A table entry is picked by comparing the window with all 16 indices, so every entry is read and no memory index
 *    depends on the secret.
 *  - The addition is the unified formula for a = -1 (add-2008-hwcd-3). Its d is not a square, so the formula is
 *    complete: it doubles and handles the identity without a special case.
 *  - The inversion and the field operations are BouncyCastle's, and they have no data-dependent branch.
 * The child scalars kL + 8*Z_L and kR + Z_R are added byte by byte, modulo 2^256.
 */
object LedgerEd25519 {
    private const val SEED_BYTES = 64
    private const val HARDENED = Int.MIN_VALUE // 0x80000000 as an Int
    private const val HMAC_SHA256 = "HmacSHA256"
    private const val HMAC_SHA512 = "HmacSHA512"
    private val ED25519_SEED = "ed25519 seed".toByteArray(Charsets.US_ASCII)

    // Field elements are X25519Field arrays of 10 limbs, modulo 2^255 - 19. A Point holds X, Y, Z and T with
    // x = X/Z, y = Y/Z and T = X*Y/Z, each coordinate one field element. The order of these declarations matters: each
    // value is built from the ones above it.
    private val CURVE_D: IntArray = feNeg(feMul(feSmall(121665), feInv(feSmall(121666)))) // -121665 / 121666
    private val TWO_D: IntArray = feMul(CURVE_D, feSmall(2))
    private val BASE: Point = basePoint()
    private val MULTIPLES: IntArray = baseMultiples() // entries 0*B to 15*B, 40 limbs each

    /**
     * An account key. [publicKey] is the 32-byte Ed25519 key the device shows, and [AlgoAddress.encode] turns it into
     * the address. [kL] is the 32-byte seed the device signs with. [kR] is the other half of the extended key,
     * which only child derivation uses. [chainCode] is the 32-byte chain code at the end of the path. Like a BIP32 chain code it is public,
     * and a pairing carries it. [kL] and [kR] are secret; the caller calls [wipe] when it is done.
     */
    class Key internal constructor(val publicKey: ByteArray, val kL: ByteArray, val kR: ByteArray, val chainCode: ByteArray) {
        /** Zeroes [kL] and [kR]. The public key and the chain code are not secret and are left as they are. */
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
        val kL = extended.copyOfRange(0, 32)
        val kR = extended.copyOfRange(32, 64)
        extended.fill(0)
        return try {
            Key(publicKey(kL), kL, kR, chain)
        } catch (e: Throwable) {
            kL.fill(0)
            kR.fill(0)
            throw e
        }
    }

    /** The RFC 8032 public key of the seed [kL]: clamp(SHA-512(kL)[0..32]) times the base point. */
    private fun publicKey(kL: ByteArray): ByteArray = Ed25519PrivateKeyParameters(kL, 0).generatePublicKey().encoded

    private fun hardened(index: Int): Int = index or HARDENED

    /** The extended key kL||kR and chain code at the end of [path]: BOLOS expand, then one step per index. */
    private fun derive(seed: ByteArray, path: IntArray): Pair<ByteArray, ByteArray> {
        var extended = hmac(HMAC_SHA512, ED25519_SEED, seed)
        var chain = ByteArray(0)
        try {
            while ((extended[31].toInt() and 0x20) != 0) {
                val next = hmac(HMAC_SHA512, ED25519_SEED, extended)
                extended.fill(0)
                extended = next
            }
            extended[0] = (extended[0].toInt() and 0xF8).toByte()
            extended[31] = ((extended[31].toInt() and 0x7F) or 0x40).toByte()
            chain = hmac(HMAC_SHA256, ED25519_SEED, byteArrayOf(0x01), seed)
            for (index in path) {
                val (nextExtended, nextChain) = childStep(extended, chain, index)
                extended.fill(0)
                chain.fill(0)
                extended = nextExtended
                chain = nextChain
            }
            return extended to chain
        } catch (e: Throwable) {
            extended.fill(0)
            chain.fill(0)
            throw e
        }
    }

    /** One child step of BOLOS `hdw_bip32_ed25519`: the child kL||kR and chain code for [index]. */
    private fun childStep(extended: ByteArray, chain: ByteArray, index: Int): Pair<ByteArray, ByteArray> {
        // Every secret and HMAC buffer made below is listed here, and all of them are zeroed on return or on a throw.
        val secrets = mutableListOf<ByteArray>()
        try {
            val kL = extended.copyOfRange(0, 32).also(secrets::add)
            val kR = extended.copyOfRange(32, 64).also(secrets::add)
            val indexBytes = littleEndian(index)
            val isHardened = index < 0
            // The parent's point A = kL*G. Only non-hardened steps use it, in the Z input and the chain-code input.
            val point = if (isHardened) ByteArray(0) else scalarMultBaseEncoded(kL)
            val z =
                (
                    if (isHardened) hmac(HMAC_SHA512, chain, byteArrayOf(0x00), kL, kR, indexBytes)
                    else hmac(HMAC_SHA512, chain, byteArrayOf(0x02), point, indexBytes)
                ).also(secrets::add)
            // Z_L keeps 28 bytes: its top 32 bits (bytes 28..31, little-endian) are cleared.
            // Then kL' = kL + 8 * Z_L (mod 2^256).
            val zL = z.copyOfRange(0, 32).also(secrets::add)
            zL.fill(0, 28, 32)
            val zR = z.copyOfRange(32, 64).also(secrets::add)
            val scaledZL = timesEightMod256(zL).also(secrets::add)
            val childKL = addMod256(kL, scaledZL).also(secrets::add)
            val childKR = addMod256(kR, zR).also(secrets::add)
            // The chain code uses the parent's kL||kR when hardened, and the parent's point A otherwise.
            val chainMac =
                (
                    if (isHardened) hmac(HMAC_SHA512, chain, byteArrayOf(0x01), kL, kR, indexBytes)
                    else hmac(HMAC_SHA512, chain, byteArrayOf(0x03), point, indexBytes)
                ).also(secrets::add)
            return (childKL + childKR) to chainMac.copyOfRange(32, 64)
        } finally {
            secrets.forEach { it.fill(0) }
        }
    }

    /**
     * [scalar] times the base point, encoded per RFC 8032. [scalar] is 32 little-endian bytes, read as the integer
     * they encode, with no clamping and no reduction. Internal, so the tests can check the signing expansion against
     * the same arithmetic.
     */
    internal fun scalarMultBaseEncoded(scalar: ByteArray): ByteArray {
        require(scalar.size == 32) { "scalar must be 32 bytes" }
        return encode(scalarMultBase(scalar))
    }

    /** HMAC of [parts] in order. The parts are fed one by one, so no joined copy of a secret is left behind. */
    private fun hmac(algorithm: String, key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        for (part in parts) mac.update(part)
        return mac.doFinal()
    }

    private fun littleEndian(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    /** [a] + [b] mod 2^256 for 32-byte little-endian values, with the carry added byte by byte. */
    private fun addMod256(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(32)
        var carry = 0
        for (i in 0 until 32) {
            val sum = (a[i].toInt() and 0xFF) + (b[i].toInt() and 0xFF) + carry
            out[i] = sum.toByte()
            carry = sum ushr 8
        }
        return out
    }

    /** 8 * [a] mod 2^256 for a 32-byte little-endian value: the top three bits of each byte move into the next byte. */
    private fun timesEightMod256(a: ByteArray): ByteArray {
        val out = ByteArray(32)
        var carry = 0
        for (i in 0 until 32) {
            val value = a[i].toInt() and 0xFF
            out[i] = ((value shl 3) or carry).toByte()
            carry = value ushr 5
        }
        return out
    }

    // BouncyCastle's X25519Field works on int arrays of 10 limbs. Its add and sub do not carry, so a sum or difference
    // goes only into a multiplication, never into another add or sub. Every operand below follows that rule.

    private fun feNew(): IntArray = X25519Field.create()

    private fun feOne(): IntArray = feNew().also { X25519Field.one(it) }

    /** The field element for a non-negative [value] below 2^32. */
    private fun feSmall(value: Int): IntArray {
        val bytes = ByteArray(32)
        for (i in 0 until 4) bytes[i] = (value ushr (8 * i)).toByte()
        return feNew().also { X25519Field.decode(bytes, 0, it) }
    }

    private fun feAdd(a: IntArray, b: IntArray): IntArray = feNew().also { X25519Field.add(a, b, it) }

    private fun feSub(a: IntArray, b: IntArray): IntArray = feNew().also { X25519Field.sub(a, b, it) }

    private fun feMul(a: IntArray, b: IntArray): IntArray = feNew().also { X25519Field.mul(a, b, it) }

    private fun feNeg(a: IntArray): IntArray = feNew().also { X25519Field.negate(a, it) }

    /** The inverse by BouncyCastle's constant-time inversion. [a] is never zero for a point on the curve. */
    private fun feInv(a: IntArray): IntArray = feNew().also { X25519Field.inv(a, it) }

    /** A point in extended twisted Edwards coordinates. */
    private class Point(val x: IntArray, val y: IntArray, val z: IntArray, val t: IntArray)

    private fun identity(): Point = Point(feNew(), feOne(), feOne(), feNew())

    /**
     * The base point: y = 4/5 and the even x with x^2 = (y^2 - 1) / (d*y^2 + 1), as RFC 8032 section 5.1 gives it. The
     * inputs are public, so the variable-time square root is used here only.
     */
    private fun basePoint(): Point {
        val y = feMul(feSmall(4), feInv(feSmall(5)))
        val y2 = feMul(y, y)
        // The multiplications by one turn the sums into carried values, as sqrtRatioVar expects.
        val u = feMul(feSub(y2, feOne()), feOne())
        val v = feMul(feAdd(feMul(CURVE_D, y2), feOne()), feOne())
        val x = feNew()
        check(X25519Field.sqrtRatioVar(u, v, x)) { "the base point has no square root" }
        if (isOdd(x)) X25519Field.cnegate(1, x)
        return Point(x, y, feOne(), feMul(x, y))
    }

    /** The low bit of the canonical value of [a]. */
    private fun isOdd(a: IntArray): Boolean {
        val canonical = feNew().also { X25519Field.copy(a, 0, it, 0) }
        X25519Field.normalize(canonical)
        val bytes = ByteArray(32)
        X25519Field.encode(canonical, bytes, 0)
        return (bytes[0].toInt() and 1) == 1
    }

    /** The unified addition for a = -1 (add-2008-hwcd-3, Hisil et al.). It is complete, so it also doubles. */
    private fun addPoints(p: Point, q: Point): Point {
        val a = feMul(feSub(p.y, p.x), feSub(q.y, q.x))
        val b = feMul(feAdd(p.y, p.x), feAdd(q.y, q.x))
        val c = feMul(feMul(p.t, q.t), TWO_D)
        val d = feMul(p.z, feAdd(q.z, q.z))
        val e = feSub(b, a)
        val f = feSub(d, c)
        val g = feAdd(d, c)
        val h = feAdd(b, a)
        return Point(feMul(e, f), feMul(g, h), feMul(f, g), feMul(e, h))
    }

    /** The entries 0*B to 15*B, each as its four coordinates of 10 limbs, one entry after another. */
    private fun baseMultiples(): IntArray {
        val table = IntArray(16 * 40)
        var multiple = identity()
        for (j in 0 until 16) {
            putPoint(table, j * 40, multiple)
            multiple = addPoints(multiple, BASE)
        }
        return table
    }

    private fun putPoint(table: IntArray, offset: Int, p: Point) {
        X25519Field.copy(p.x, 0, table, offset)
        X25519Field.copy(p.y, 0, table, offset + 10)
        X25519Field.copy(p.z, 0, table, offset + 20)
        X25519Field.copy(p.t, 0, table, offset + 30)
    }

    /**
     * The table entry for [digit], from 0 to 15. All 16 entries are read and the ones that do not match are masked
     * out, so the memory access does not depend on [digit].
     */
    private fun selectMultiple(digit: Int): Point {
        val picked = IntArray(40)
        for (j in 0 until 16) {
            val mask = equalMask(digit, j)
            // cmov moves one field element (10 limbs), so each of the four coordinates is moved on its own.
            for (coordinate in 0 until 4) {
                X25519Field.cmov(mask, MULTIPLES, j * 40 + coordinate * 10, picked, coordinate * 10)
            }
        }
        return Point(
            picked.copyOfRange(0, 10),
            picked.copyOfRange(10, 20),
            picked.copyOfRange(20, 30),
            picked.copyOfRange(30, 40),
        )
    }

    /** -1 if [a] equals [b], else 0. The xor is zero only for equal values, so no branch is needed. */
    private fun equalMask(a: Int, b: Int): Int {
        val diff = a xor b
        return ((diff or -diff) shr 31) xor -1
    }

    /**
     * [scalar] times the base point. The work is the same for every scalar: 64 four-bit windows, most significant
     * first, each four doublings and one addition of the selected multiple (the identity when the window is zero).
     */
    private fun scalarMultBase(scalar: ByteArray): Point {
        var acc = identity()
        for (window in 63 downTo 0) {
            repeat(4) { acc = addPoints(acc, acc) }
            val digit = (scalar[window ushr 1].toInt() ushr ((window and 1) shl 2)) and 0xF
            acc = addPoints(acc, selectMultiple(digit))
        }
        return acc
    }

    /** RFC 8032 point encoding: y little-endian, with the low bit of x in the top bit of the last byte. */
    private fun encode(p: Point): ByteArray {
        val zInv = feInv(p.z)
        val x = feMul(p.x, zInv)
        val y = feMul(p.y, zInv)
        X25519Field.normalize(x)
        X25519Field.normalize(y)
        val out = ByteArray(32)
        X25519Field.encode(y, out, 0)
        val xBytes = ByteArray(32)
        X25519Field.encode(x, xBytes, 0)
        out[31] = (out[31].toInt() or ((xBytes[0].toInt() and 1) shl 7)).toByte()
        return out
    }
}
