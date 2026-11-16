package app.lensly.app

import app.lensly.core.btc.BtcFeeThresholds

/*
 * The changes the Settings screens save as the owner makes them. Each returns the settings with one value changed and every other
 * value kept, or null when the new value is outside its range, so the screen saves nothing for a value it refuses.
 */

/** The settings with the shortest hide delay [minMs], or null when the range with the saved longest delay is not one Settings accepts. */
fun AppSettings.withHideDelayMin(minMs: Long): AppSettings? = HideDelay.of(minMs, hideDelay.maxMs)?.let { copy(hideDelay = it) }

/** The settings with the longest hide delay [maxMs], or null when the range with the saved shortest delay is not one Settings accepts. */
fun AppSettings.withHideDelayMax(maxMs: Long): AppSettings? = HideDelay.of(hideDelay.minMs, maxMs)?.let { copy(hideDelay = it) }

/** The settings with the Bitcoin fee rate limit [satPerVb], or null when it is outside its range. */
fun AppSettings.withFeeRate(satPerVb: Int): AppSettings? =
    BtcFeeThresholds.of(satPerVb, bitcoinFeeThresholds.feeSharePercent)?.let { copy(bitcoinFeeThresholds = it) }

/** The settings with the Bitcoin fee share limit [percent], or null when it is outside its range. */
fun AppSettings.withFeeShare(percent: Int): AppSettings? =
    BtcFeeThresholds.of(bitcoinFeeThresholds.highFeeRateSatPerVb, percent)?.let { copy(bitcoinFeeThresholds = it) }
