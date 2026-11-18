package app.lensly.core.solana

import fr.acinq.bitcoin.Base58
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The classic SPL token account of an owner for a mint: the associated token account (ATA), derived the way the ATA program derives it. An ATA
 * is a program-derived address, so it lies off the Ed25519 curve. The derivation tries bumps from 255 down, as Solana does, and keeps the first
 * hash that is off the curve.
 */
object SolanaTokenAccounts {
    /** The classic SPL Token program, in base58. */
    const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"

    /** The Associated Token Account program, in base58. */
    const val ASSOCIATED_TOKEN_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"

    private val MARKER = "ProgramDerivedAddress".toByteArray(Charsets.US_ASCII)
    private val ONE = BigInteger.ONE

    /** The field prime of Curve25519, 2^255 - 19. */
    private val PRIME = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))

    /** The curve constant d, which is -121665 / 121666 modulo the prime. */
    private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(PRIME)).mod(PRIME)

    /** The 32-byte address of the associated token account of [owner] for [mint], in the classic token program. */
    fun associatedAddress(owner: ByteArray, mint: ByteArray): ByteArray =
        programAddress(listOf(owner, Base58.decode(TOKEN_PROGRAM), mint), Base58.decode(ASSOCIATED_TOKEN_PROGRAM))

    /** The program-derived address of [seeds] under [programId]: the hash at the highest bump whose value is off the curve. */
    internal fun programAddress(seeds: List<ByteArray>, programId: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (bump in 255 downTo 0) {
            digest.reset()
            seeds.forEach { digest.update(it) }
            digest.update(bump.toByte())
            digest.update(programId)
            digest.update(MARKER)
            val candidate = digest.digest()
            if (!onCurve(candidate)) return candidate
        }
        throw IllegalStateException("No program address exists for these seeds.")
    }

    /**
     * Whether [encoded], 32 bytes, is a point of the Ed25519 curve. The low 255 bits are y, little-endian, and the top bit is the sign of x, which
     * does not matter here. x squared is (y squared minus 1) over (d y squared plus 1), so the point exists exactly when that ratio is a square
     * (Euler's criterion), or zero.
     */
    internal fun onCurve(encoded: ByteArray): Boolean {
        val bigEndian = encoded.reversedArray()
        bigEndian[0] = (bigEndian[0].toInt() and 0x7f).toByte()
        val y = BigInteger(1, bigEndian).mod(PRIME)
        val ySquared = y.multiply(y).mod(PRIME)
        val numerator = ySquared.subtract(ONE).mod(PRIME)
        val denominator = D.multiply(ySquared).add(ONE).mod(PRIME)
        if (denominator.signum() == 0) return false
        val ratio = numerator.multiply(denominator.modInverse(PRIME)).mod(PRIME)
        if (ratio.signum() == 0) return true
        return ratio.modPow(PRIME.subtract(ONE).shiftRight(1), PRIME) == ONE
    }
}
