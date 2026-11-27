package app.lensly.core.registry

/**
 * The network constants behind the fee a Solana review shows. A signed registry package can carry its own, so a change on the network does
 * not leave a locked build showing the wrong Max Fee; a build without one uses [DEFAULT].
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
        require(lamportsPerSignature > 0) { "lamportsPerSignature must be positive" }
        require(builtinUnitsPerInstruction in 1..UNITS_LIMIT) { "builtinUnitsPerInstruction must be 1 to $UNITS_LIMIT" }
        require(defaultUnitsPerInstruction in 1..UNITS_LIMIT) { "defaultUnitsPerInstruction must be 1 to $UNITS_LIMIT" }
        require(maxUnits in 1..UNITS_LIMIT) { "maxUnits must be 1 to $UNITS_LIMIT" }
    }

    companion object {
        /** The largest unit count a review computes with, the largest `UInt`. */
        private const val UNITS_LIMIT = 0xFFFF_FFFFL

        /** The values of the network when this build was made: 5,000 lamports a signature, 200,000 and 3,000 units (SIMD-0170), a 1,400,000 cap. */
        val DEFAULT = SolanaFees(
            lamportsPerSignature = 5_000,
            defaultUnitsPerInstruction = 200_000,
            builtinUnitsPerInstruction = 3_000,
            maxUnits = 1_400_000,
        )
    }
}
