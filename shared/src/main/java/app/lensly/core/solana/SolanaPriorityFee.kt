package app.lensly.core.solana

import fr.acinq.bitcoin.Base58
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The priority fee of a Solana send, which the send's Priority Fee switch adds: a ComputeBudget SetComputeUnitPrice instruction, placed right after the
 * nonce advance. Lumio sets no compute limit, so the network gives each instruction its default units, and the fee is the price times those units,
 * rounded up to a whole lamport. Lensly reads the same instruction and shows the same fee, as an upper bound.
 */
object SolanaPriorityFee {
    /** The price the switch pays, in micro-lamports per compute unit. */
    const val MICRO_LAMPORTS_PER_UNIT = 100_000L

    /** The default compute units of a builtin instruction: a System Program one, or the compute budget's own SetComputeUnitPrice. */
    const val SYSTEM_UNITS = 3_000L

    /** The default compute units of an instruction of any other program. */
    const val PROGRAM_UNITS = 200_000L

    /** The ComputeBudget program. */
    const val PROGRAM = "ComputeBudget111111111111111111111111111111"

    /** SetComputeUnitPrice is instruction tag 3. */
    private const val SET_COMPUTE_UNIT_PRICE = 3

    /** The data of SetComputeUnitPrice at [MICRO_LAMPORTS_PER_UNIT]: its tag, then the price as a u64 little-endian. */
    fun instructionData(): ByteArray = ByteBuffer.allocate(1 + 8).order(ByteOrder.LITTLE_ENDIAN)
        .put(SET_COMPUTE_UNIT_PRICE.toByte())
        .putLong(MICRO_LAMPORTS_PER_UNIT)
        .array()

    /** The priority fee of [units] compute units at [MICRO_LAMPORTS_PER_UNIT], in lamports, rounded up. */
    fun lamports(units: Long): Long = (MICRO_LAMPORTS_PER_UNIT * units + MICRO_LAMPORTS_PER_LAMPORT - 1) / MICRO_LAMPORTS_PER_LAMPORT

    /** The ComputeBudget program's key. */
    fun programKey(): ByteArray = Base58.decode(PROGRAM)

    private const val MICRO_LAMPORTS_PER_LAMPORT = 1_000_000L
}
