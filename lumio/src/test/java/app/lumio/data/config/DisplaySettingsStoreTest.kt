package app.lumio.data.config

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import kotlin.test.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun theDefaultsAreWhatTheOwnerSeesBeforeAnyChange() {
        val session = open()

        val settings = DisplaySettingsStore.read(session)

        assertEquals(DisplaySettings(), settings)
        assertTrue(settings.showTotal)
        assertTrue(settings.showAssets)
        session.close()
    }

    @Test
    fun aRecordThatDoesNotParseIsKeptAndTheWriteRefused() {
        val session = open()
        val damaged = """{"showTotal":"yes","currency":"CHF"}"""
        session.putVaultRecord(DisplaySettingsStore.RECORD, damaged.encodeToByteArray())

        assertEquals(DisplaySettings(), DisplaySettingsStore.read(session))
        assertThrows(IllegalStateException::class.java) { DisplaySettingsStore.write(session, DisplaySettings(showAssets = false)) }

        assertEquals(damaged, session.getVaultRecord(DisplaySettingsStore.RECORD)!!.decodeToString())
        session.close()
    }

    @Test
    fun everyFieldIsStoredSoALaterDefaultDoesNotChangeTheRecord() {
        val session = open()
        DisplaySettingsStore.write(session, DisplaySettings())

        val record = session.getVaultRecord(DisplaySettingsStore.RECORD)!!.decodeToString()
        assertTrue(record, """"showTotal":true""" in record)
        assertTrue(record, """"smallBalanceUsd":1.0""" in record)
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
}
