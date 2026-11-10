package app.lensly.core.vault

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class KdfCalibrationTest {
    /** A simulated device. Time scales with iterations and with memory, relative to 64 MiB. */
    private fun device(msPerIterationAt64MiB: Long): (KdfParams) -> Long = { p ->
        val memoryScale = p.memoryKiB.toDouble() / KdfCalibration.DEFAULT_MEMORY_KIB
        (msPerIterationAt64MiB * memoryScale * p.iterations).toLong()
    }

    @Test
    fun fastDeviceRaisesIterationsUntilOneDerivationReachesTheTarget() {
        // 100 ms per iteration: nine iterations give 900 ms.
        assertEquals(KdfParams(memoryKiB = 64 * 1024, iterations = 9), KdfCalibration.calibrate(device(100)))
    }

    @Test
    fun veryFastDeviceStopsAtTheIterationCap() {
        assertEquals(KdfParams(memoryKiB = 64 * 1024, iterations = 10), KdfCalibration.calibrate(device(5)))
    }

    @Test
    fun deviceWhereOneIterationAlreadyTakesTheTargetKeepsOneIteration() {
        assertEquals(KdfParams(memoryKiB = 64 * 1024, iterations = 1), KdfCalibration.calibrate(device(1_000)))
    }

    @Test
    fun slowDeviceStepsMemoryDownToThirtyTwoMiB() {
        // 1600 ms at 64 MiB and one iteration is over the 1.5 s limit. At 32 MiB it takes 800 ms,
        // so two iterations are needed.
        assertEquals(KdfParams(memoryKiB = 32 * 1024, iterations = 2), KdfCalibration.calibrate(device(1_600)))
    }

    @Test
    fun exactlyTheFirstPassLimitDoesNotStepMemoryDown() {
        val params = KdfCalibration.calibrate { p -> if (p.memoryKiB == 64 * 1024) 1_500L else 1L }
        assertEquals(KdfParams(memoryKiB = 64 * 1024, iterations = 1), params)
    }

    @Test
    fun keepsSteppingWhenTheLinearEstimateFallsShort() {
        // The first measurement suggests 3 iterations, but the measured time at 3 is still under the target.
        val measured = mutableListOf<KdfParams>()
        val params = KdfCalibration.calibrate { p ->
            measured += p
            if (p.memoryKiB != 64 * 1024) {
                0L
            } else {
                when (p.iterations) {
                    1 -> 400L
                    2 -> 700L
                    3 -> 850L
                    else -> 950L
                }
            }
        }
        assertEquals(KdfParams(memoryKiB = 64 * 1024, iterations = 4), params)
        assertEquals(KdfParams(memoryKiB = 64 * 1024, iterations = 4), measured.last())
        assertTrue(measured.any { it.iterations == 3 })
    }
}
