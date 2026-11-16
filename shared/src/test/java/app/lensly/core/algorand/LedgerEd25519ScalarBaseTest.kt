package app.lensly.core.algorand

import app.lensly.core.wallet.toHex
import java.math.BigInteger
import java.util.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Checks [LedgerEd25519.scalarMultBaseEncoded] against the BigInteger ladder that it replaced. That ladder is kept here
 * as the reference only: the production code does not use BigInteger, and the reference is not constant time.
 */
class LedgerEd25519ScalarBaseTest {

    @Test
    fun matchesTheBigIntegerLadderForRandomScalars() {
        val random = Random(SEED)
        repeat(RANDOM_SCALARS) {
            assertMatchesReference(ByteArray(32).also(random::nextBytes))
        }
    }

    @Test
    fun matchesTheBigIntegerLadderForEdgeScalars() {
        val edges = listOf(
            BigInteger.ZERO,
            BigInteger.ONE,
            BigInteger.valueOf(8),
            ORDER.subtract(BigInteger.ONE),
            ORDER,
            ORDER.add(BigInteger.ONE),
            BigInteger.ONE.shiftLeft(255),
            BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE),
            BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE),
        )
        for (value in edges) {
            assertMatchesReference(leBytes(value))
        }
    }

    @Test
    fun knownAnswersForOneZeroAndTheGroupOrder() {
        assertEquals(BASE_ENCODED, LedgerEd25519.scalarMultBaseEncoded(leBytes(BigInteger.ONE)).toHex())
        assertEquals(IDENTITY_ENCODED, LedgerEd25519.scalarMultBaseEncoded(ByteArray(32)).toHex())
        assertEquals(IDENTITY_ENCODED, LedgerEd25519.scalarMultBaseEncoded(leBytes(ORDER)).toHex())
    }

    private fun assertMatchesReference(scalar: ByteArray) {
        assertContentEquals(
            ReferenceLadder.scalarMultBaseEncoded(scalar),
            LedgerEd25519.scalarMultBaseEncoded(scalar),
            "scalar ${scalar.toHex()}",
        )
    }

    private fun leBytes(value: BigInteger): ByteArray {
        val be = value.toByteArray()
        return ByteArray(32) { i ->
            val at = be.size - 1 - i
            if (at >= 0) be[at] else 0.toByte()
        }
    }

    /** The BigInteger scalar multiplication that [LedgerEd25519] used before, copied unchanged. Test code only. */
    private object ReferenceLadder {
        private val FIELD: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
        private val CURVE_D: BigInteger =
            BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(FIELD)).mod(FIELD)
        private val BASE: Point = basePoint()
        private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

        fun scalarMultBaseEncoded(scalar: ByteArray): ByteArray =
            encode(scalarMult(BASE, BigInteger(1, scalar.reversedArray())))

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
                val sqrtMinusOne = BigInteger.valueOf(2).modPow(FIELD.subtract(BigInteger.ONE).shiftRight(2), FIELD)
                x = x.multiply(sqrtMinusOne).mod(FIELD)
            }
            return if (x.testBit(0)) FIELD.subtract(x) else x
        }

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

        private fun encode(p: Point): ByteArray {
            val zInv = p.z.modInverse(FIELD)
            val x = p.x.multiply(zInv).mod(FIELD)
            val y = p.y.multiply(zInv).mod(FIELD)
            val be = y.toByteArray()
            val out = ByteArray(32) { i ->
                val at = be.size - 1 - i
                if (at >= 0) be[at] else 0.toByte()
            }
            if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
            return out
        }
    }

    private companion object {
        const val SEED = 20261116L
        const val RANDOM_SCALARS = 200

        /** The RFC 8032 base point B, which is 1*B. */
        const val BASE_ENCODED = "5866666666666666666666666666666666666666666666666666666666666666"

        /** The identity point, which 0*B and L*B both give. */
        const val IDENTITY_ENCODED = "0100000000000000000000000000000000000000000000000000000000000000"

        /** The Ed25519 group order L = 2^252 + 27742317777372353535851937790883648493. */
        val ORDER: BigInteger =
            BigInteger.valueOf(2).pow(252).add(BigInteger("27742317777372353535851937790883648493"))
    }
}
