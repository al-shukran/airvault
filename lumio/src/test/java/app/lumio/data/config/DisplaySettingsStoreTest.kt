package app.lumio.data.config

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import kotlin.test.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The display settings: their defaults and what the vault keeps of them. */
class DisplaySettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()

    // A session zeroes the key it is given when it closes, so each session gets its own copy.
    private fun open(): VaultSession = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it * 9 + 2).toByte() })

    @Test
    fun theDefaultsAreTheLumio1Defaults() {
        val session = open()

        val settings = DisplaySettingsStore.read(session)

        assertEquals(DisplaySettings(), settings)
        assertFalse(settings.showTotal)
        assertTrue(settings.showAssets)
        session.close()
    }

    @Test
    fun aSavedChangeIsReadBackAfterTheVaultReopens() {
        val session = open()
        DisplaySettingsStore.write(session, DisplaySettings(showTotal = true, showAssets = false))
        session.close()

        val reopened = open()
        assertEquals(DisplaySettings(showTotal = true, showAssets = false), DisplaySettingsStore.read(reopened))
        reopened.close()
    }

    @Test
    fun theCurrencyAndTheSmallBalanceThresholdAreReadBackAfterTheVaultReopens() {
        val session = open()
        DisplaySettingsStore.write(session, DisplaySettings(currency = FiatCurrency.CHF, smallBalanceUsd = 2.5))
        session.close()

        val reopened = open()
        assertEquals(DisplaySettings(currency = FiatCurrency.CHF, smallBalanceUsd = 2.5), DisplaySettingsStore.read(reopened))
        reopened.close()
    }

    @Test
    fun aThresholdOffTheSliderReadsAsTheDefault() {
        val session = open()
        DisplaySettingsStore.write(session, DisplaySettings(showTotal = true, smallBalanceUsd = 2.25))
        session.close()

        val reopened = open()
        assertEquals(DisplaySettings(showTotal = true), DisplaySettingsStore.read(reopened))
        reopened.close()
    }

    // The records below are what Lumio 1.0.0 stored: its Json left out a field equal to its default (showTotal false, showAssets true).
    private fun stored(record: String): DisplaySettings {
        val session = open()
        session.putVaultRecord(DisplaySettingsStore.RECORD, record.encodeToByteArray())
        return DisplaySettingsStore.read(session).also { session.close() }
    }

    @Test
    fun aVersion1RecordWithOnlyTheBalancesHiddenKeepsTheTotalHidden() {
        assertEquals(DisplaySettings(showTotal = false, showAssets = false), stored("""{"showAssets":false}"""))
    }

    @Test
    fun aVersion1RecordWithTheTotalHiddenAndBalancesShownKeepsBothAsTheyWere() {
        assertEquals(DisplaySettings(showTotal = false, showAssets = true), stored("{}"))
    }

    @Test
    fun aVersion1RecordWithTheTotalShownAndBalancesShownKeepsBoth() {
        assertEquals(DisplaySettings(showTotal = true, showAssets = true), stored("""{"showTotal":true}"""))
    }

    @Test
    fun aVersion1RecordWithTheTotalShownAndBalancesHiddenKeepsBothAsTheyWere() {
        assertEquals(DisplaySettings(showTotal = true, showAssets = false), stored("""{"showTotal":true,"showAssets":false}"""))
    }

    @Test
    fun aSavedRecordWithTheTotalShownKeepsItShown() {
        val session = open()
        DisplaySettingsStore.write(session, DisplaySettings(showTotal = true, currency = FiatCurrency.CHF))
        session.close()

        val reopened = open()
        assertEquals(DisplaySettings(showTotal = true, currency = FiatCurrency.CHF), DisplaySettingsStore.read(reopened))
        reopened.close()
    }
}
