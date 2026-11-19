package app.airvault.chains.bitcoin.signer

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BtcFeeThresholdsTest {
    private val limits = BtcFeeThresholds(highFeeRateSatPerVb = 200, feeSharePercent = 10)

    @Test
    fun aFeeRateOnTheLimitIsNotAWarningAndOneSatAboveIsOne() {
        // 141 vbytes at 200 sat/vB is 28,200 sat exactly.
        assertEquals(emptyList(), limits.warnings(feeSats = 28_200, paidSats = 0, estimatedVsize = 141))
        assertEquals(
            listOf(BtcWarning.HighFeeRate(28_201.0 / 141, 200)),
            limits.warnings(feeSats = 28_201, paidSats = 0, estimatedVsize = 141),
        )
    }

    @Test
    fun aFeeShareOnTheLimitIsNotAWarningAndOneSatAboveIsOne() {
        // 10 per cent of 50,000 sat paid is 5,000 sat exactly.
        assertEquals(emptyList(), limits.warnings(feeSats = 5_000, paidSats = 50_000, estimatedVsize = 1_000))
        assertEquals(
            listOf(BtcWarning.HighFeeShare(5_001.0 * 100 / 50_000, 10)),
            limits.warnings(feeSats = 5_001, paidSats = 50_000, estimatedVsize = 1_000),
        )
    }

    @Test
    fun aRequestThatSendsNothingToOthersHasNoShareToCheck() {
        assertEquals(emptyList(), limits.warnings(feeSats = 1_000, paidSats = 0, estimatedVsize = 1_000))
    }

    @Test
    fun bothLimitsCanBeExceededAtOnce() {
        val warnings = limits.warnings(feeSats = 100_000, paidSats = 100_000, estimatedVsize = 100)
        assertEquals(2, warnings.size)
        assertTrue(warnings[0] is BtcWarning.HighFeeRate)
        assertTrue(warnings[1] is BtcWarning.HighFeeShare)
    }

    @Test
    fun aZeroFeeIsNeverAWarning() {
        assertEquals(emptyList(), limits.warnings(feeSats = 0, paidSats = 1, estimatedVsize = 1))
    }

    @Test
    fun aRateLimitIsCheckedWithAnyPositiveSize() {
        assertEquals(
            listOf(BtcWarning.HighFeeRate(1_000.0, 200)),
            limits.warnings(feeSats = 1_000, paidSats = 0, estimatedVsize = 1),
        )
    }

    @Test
    fun aRateLimitIsSkippedForAZeroSize() {
        assertEquals(emptyList(), limits.warnings(feeSats = 1_000, paidSats = 0, estimatedVsize = 0))
    }

    @Test
    fun theDefaultsAreTwoHundredSatPerVbAndATenthOfTheAmount() {
        assertEquals(BtcFeeThresholds(200, 10), BtcFeeThresholds.DEFAULT)
    }

    @Test
    fun aRateLimitOutsideOneToTenThousandIsRefused() {
        assertNull(BtcFeeThresholds.of(0, 10))
        assertNull(BtcFeeThresholds.of(10_001, 10))
        assertEquals(BtcFeeThresholds(1, 10), BtcFeeThresholds.of(1, 10))
        assertEquals(BtcFeeThresholds(10_000, 10), BtcFeeThresholds.of(10_000, 10))
    }

    @Test
    fun aShareLimitOutsideOneToOneHundredPerCentIsRefused() {
        assertNull(BtcFeeThresholds.of(200, 0))
        assertNull(BtcFeeThresholds.of(200, 101))
        assertEquals(BtcFeeThresholds(200, 1), BtcFeeThresholds.of(200, 1))
        assertEquals(BtcFeeThresholds(200, 100), BtcFeeThresholds.of(200, 100))
    }
}
