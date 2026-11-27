package app.lensly.core.registry

/**
 * The network constants behind the fee a Solana review shows. A signed registry package can carry its own, so a change on the network does
 * not leave a locked build showing the wrong Max Fee; a build without one uses [DEFAULT]. Each constant is at least the build's own
 * and at most [CEILING_FACTOR] times it, so a package can raise the fee a review shows but never understate it, and the fee cannot wrap.
 *
 * [lamportsPerSignature] is the base fee of one signature. [defaultUnitsPerInstruction] is the compute units an instruction of any other
 * program gets when its transaction sets no limit, and [builtinUnitsPerInstruction] the units a System Program or compute budget
 * instruction gets. [maxUnits] is the largest compute limit a transaction can request.
 */
data class SolanaFees(
    val lamportsPerSignature: Long,
    val defaultUnitsPerInstruction: Long,
    val builtinUnitsPerInstruction: Long,
    val maxUnits: Long,
) {
    init {
        requireBounded("lamportsPerSignature", lamportsPerSignature, LAMPORTS)
        requireBounded("builtinUnitsPerInstruction", builtinUnitsPerInstruction, BUILTIN_UNITS)
        requireBounded("defaultUnitsPerInstruction", defaultUnitsPerInstruction, DEFAULT_UNITS)
        requireBounded("maxUnits", maxUnits, MAX_UNITS)
    }

    companion object {
        /** How many times a build's own constant a package's may be at most. The largest ceiling, 1,400,000,000 units, stays under the largest `UInt`. */
        const val CEILING_FACTOR = 1_000L

        private const val LAMPORTS = 5_000L
        private const val DEFAULT_UNITS = 200_000L
        private const val BUILTIN_UNITS = 3_000L
        private const val MAX_UNITS = 1_400_000L

        private fun requireBounded(name: String, value: Long, floor: Long) {
            require(value in floor..floor * CEILING_FACTOR) { "$name must be $floor to ${floor * CEILING_FACTOR}" }
        }

        /** The values of the network when this build was made: 5,000 lamports a signature, 200,000 and 3,000 units (SIMD-0170), a 1,400,000 cap. */
        val DEFAULT = SolanaFees(
            lamportsPerSignature = LAMPORTS,
            defaultUnitsPerInstruction = DEFAULT_UNITS,
            builtinUnitsPerInstruction = BUILTIN_UNITS,
            maxUnits = MAX_UNITS,
        )
    }
}
