package app.lensly.app

import app.lensly.core.btc.BtcFeeThresholds
import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The bounds of each value the Settings screens change, and the persistence of each change. A change outside its range is refused
 * with null, so the screen saves nothing for it. A change keeps every other saved value, and it survives the vault being reopened.
 */
class SettingChangesTest {
    private val keys = MemoryKeystore()

    @get:Rule
    val tmp = TemporaryFolder()

    private val vaultKey = ByteArray(32) { (it * 5 + 3).toByte() }

    // A session zeroes the key array it is given when it closes, so each session gets its own copy.
    private fun open(): VaultSession = VaultSession(VaultLayout(tmp.root), keys, vaultKey.copyOf())

    private val saved = AppSettings(
        bulkCap = BulkCap.TWENTY,
        proxyPolicy = ProxyPolicy.WARN_AND_CONFIRM,
        hideDelay = HideDelay(100L, 1_000L),
        bitcoinFeeThresholds = BtcFeeThresholds(200, 10),
    )

    @Test
    fun theShortestHideDelayRunsFromZeroUpToTheLongest() {
        assertEquals(0L, assertNotNull(saved.withHideDelayMin(0L)).hideDelay.minMs)
        assertEquals(1_000L, assertNotNull(saved.withHideDelayMin(1_000L)).hideDelay.minMs)
        assertNull(saved.withHideDelayMin(1_001L))
        assertNull(saved.withHideDelayMin(-1L))
    }

    @Test
    fun theLongestHideDelayRunsFromTheShortestUpToTheLimit() {
        assertEquals(HideDelay.LIMIT_MS, assertNotNull(saved.withHideDelayMax(HideDelay.LIMIT_MS)).hideDelay.maxMs)
        assertEquals(100L, assertNotNull(saved.withHideDelayMax(100L)).hideDelay.maxMs)
        assertNull(saved.withHideDelayMax(HideDelay.LIMIT_MS + 1))
        assertNull(saved.withHideDelayMax(99L))
    }

    @Test
    fun aHideDelayChangeKeepsTheOtherBound() {
        assertEquals(HideDelay(250L, 1_000L), assertNotNull(saved.withHideDelayMin(250L)).hideDelay)
        assertEquals(HideDelay(100L, 4_000L), assertNotNull(saved.withHideDelayMax(4_000L)).hideDelay)
    }

    @Test
    fun theFeeRateRunsFromOneToTheHighestRate() {
        assertEquals(1, assertNotNull(saved.withFeeRate(1)).bitcoinFeeThresholds.highFeeRateSatPerVb)
        assertEquals(10_000, assertNotNull(saved.withFeeRate(10_000)).bitcoinFeeThresholds.highFeeRateSatPerVb)
        assertNull(saved.withFeeRate(0))
        assertNull(saved.withFeeRate(10_001))
    }

    @Test
    fun theFeeShareRunsFromOnePerCentToOneHundred() {
        assertEquals(1, assertNotNull(saved.withFeeShare(1)).bitcoinFeeThresholds.feeSharePercent)
        assertEquals(100, assertNotNull(saved.withFeeShare(100)).bitcoinFeeThresholds.feeSharePercent)
        assertNull(saved.withFeeShare(0))
        assertNull(saved.withFeeShare(101))
    }

    @Test
    fun aFeeChangeKeepsTheOtherLimit() {
        assertEquals(BtcFeeThresholds(350, 10), assertNotNull(saved.withFeeRate(350)).bitcoinFeeThresholds)
        assertEquals(BtcFeeThresholds(200, 25), assertNotNull(saved.withFeeShare(25)).bitcoinFeeThresholds)
    }

    @Test
    fun aChangedShortestHideDelayIsReadBackAfterTheVaultReopens() {
        val first = open()
        val store = SettingsStore(first)
        store.save(saved.copy(hideDelay = HideDelay.DEFAULT))
        store.save(assertNotNull(store.load().withHideDelayMin(250L)))
        first.close()

        val reopened = open()
        assertEquals(HideDelay(250L, 1_000L), SettingsStore(reopened).load().hideDelay)
        reopened.close()
    }

    @Test
    fun aChangedLongestHideDelayKeepsTheBulkCapAndProxyPolicy() {
        val first = open()
        SettingsStore(first).save(saved)
        SettingsStore(first).let { store -> store.save(assertNotNull(store.load().withHideDelayMax(4_000L))) }
        first.close()

        val reopened = open()
        val loaded = SettingsStore(reopened).load()
        assertEquals(HideDelay(100L, 4_000L), loaded.hideDelay)
        assertEquals(BulkCap.TWENTY, loaded.bulkCap)
        assertEquals(ProxyPolicy.WARN_AND_CONFIRM, loaded.proxyPolicy)
        reopened.close()
    }

    @Test
    fun aChangedFeeRateIsReadBackAndTheShareIsKept() {
        val first = open()
        SettingsStore(first).save(saved)
        SettingsStore(first).let { store -> store.save(assertNotNull(store.load().withFeeRate(350))) }
        first.close()

        val reopened = open()
        assertEquals(BtcFeeThresholds(350, 10), SettingsStore(reopened).load().bitcoinFeeThresholds)
        reopened.close()
    }

    @Test
    fun aChangedFeeShareIsReadBackAndTheRateIsKept() {
        val first = open()
        SettingsStore(first).save(saved)
        SettingsStore(first).let { store -> store.save(assertNotNull(store.load().withFeeShare(25))) }
        first.close()

        val reopened = open()
        assertEquals(BtcFeeThresholds(200, 25), SettingsStore(reopened).load().bitcoinFeeThresholds)
        reopened.close()
    }

    @Test
    fun aRefusedChangeLeavesTheSavedSettingsAsTheyWere() {
        val first = open()
        SettingsStore(first).save(saved)
        val store = SettingsStore(first)
        // Each refused change is null, so a screen writes nothing for it.
        assertNull(store.load().withHideDelayMin(2_000L))
        assertNull(store.load().withFeeRate(0))
        first.close()

        val reopened = open()
        assertEquals(saved, SettingsStore(reopened).load())
        reopened.close()
    }
}
