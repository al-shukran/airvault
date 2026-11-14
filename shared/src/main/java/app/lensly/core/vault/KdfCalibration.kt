package app.lensly.core.vault

import kotlin.math.ceil

/**
 * Chooses Argon2id parameters so that one derivation takes about [TARGET_MS] on this device.
 *
 * Memory starts at [DEFAULT_MEMORY_KIB] (64 MiB). If one iteration already takes more than
 * [FIRST_PASS_LIMIT_MS], memory steps down to [REDUCED_MEMORY_KIB] (32 MiB). Iterations then grow
 * until a derivation takes at least [TARGET_MS], and never exceed [MAX_ITERATIONS].
 *
 * [measureMs] runs one derivation with the given parameters and returns its duration in milliseconds.
 */
object KdfCalibration {
    const val TARGET_MS = 900L
    const val FIRST_PASS_LIMIT_MS = 1_500L
    const val MAX_ITERATIONS = 10
    const val DEFAULT_MEMORY_KIB = 64 * 1024
    const val REDUCED_MEMORY_KIB = 32 * 1024

    /** Small warm-up run so that the first real measurement does not include class loading and JIT. */
    private const val WARM_UP_MEMORY_KIB = 8 * 1024

    fun calibrate(measureMs: (KdfParams) -> Long): KdfParams {
        measureMs(KdfParams(WARM_UP_MEMORY_KIB, 1))

        var memory = DEFAULT_MEMORY_KIB
        var ms = measureMs(KdfParams(memory, 1))
        if (ms > FIRST_PASS_LIMIT_MS) {
            memory = REDUCED_MEMORY_KIB
            ms = measureMs(KdfParams(memory, 1))
        }

        var iterations = 1
        if (ms < TARGET_MS) {
            // Time grows roughly linearly with iterations. Jump close to the target, then confirm and keep stepping.
            iterations = ceil(TARGET_MS.toDouble() / ms.coerceAtLeast(1L)).toInt().coerceIn(2, MAX_ITERATIONS)
            ms = measureMs(KdfParams(memory, iterations))
            while (ms < TARGET_MS && iterations < MAX_ITERATIONS) {
                iterations++
                ms = measureMs(KdfParams(memory, iterations))
            }
        }
        return KdfParams(memoryKiB = memory, iterations = iterations)
    }
}
