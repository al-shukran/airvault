package app.lensly.core.solana

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SolanaPriorityFeeTest {

    /** The fee is the price times the units, in micro-lamports, rounded up to a whole lamport: 100000 micro-lamports a unit is 600 lamports for 6000 units. */
    @Test
    fun theFeeRoundsUpToAWholeLamport() {
        assertEquals(600L, SolanaPriorityFee.lamports(6_000L))
        assertEquals(20_300L, SolanaPriorityFee.lamports(203_000L))
        assertEquals(1L, SolanaPriorityFee.lamports(1L))
        assertEquals(0L, SolanaPriorityFee.lamports(0L))
    }

    /** A send with the switch on pays for three builtins: the nonce advance, the transfer and the price instruction itself, 9000 units. */
    @Test
    fun theSwitchPaysForTheComputeBudgetInstructionAsABuiltin() {
        assertEquals(3_000L, SolanaPriorityFee.SYSTEM_UNITS)
        assertEquals(900L, SolanaPriorityFee.lamports(3 * SolanaPriorityFee.SYSTEM_UNITS))
    }

    /** SetComputeUnitPrice is instruction tag 3, then the price as a u64 little-endian: 100000 is A0 86 01 and five zero bytes. */
    @Test
    fun theInstructionDataIsTheTagThenThePriceLittleEndian() {
        assertContentEquals(byteArrayOf(3, 0xA0.toByte(), 0x86.toByte(), 1, 0, 0, 0, 0, 0), SolanaPriorityFee.instructionData())
    }
}
