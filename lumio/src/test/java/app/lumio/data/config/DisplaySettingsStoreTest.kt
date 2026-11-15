package app.lumio.data.config

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.data.config.AssetSortKey.CHANGE_24H
import app.lumio.data.config.AssetSortKey.USD_VALUE
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Home display settings: their defaults, what the vault keeps of them, and the minimum the owner enters. */
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
        assertFalse(settings.showTotal)
        assertTrue(settings.showAssets)
        assertEquals(1.0, settings.minimumUsd, 0.0)
        assertTrue(settings.hideSpam)
        assertEquals(AssetSort(USD_VALUE, descending = true), settings.sort)
        session.close()
    }

    @Test
    fun theSettingsReadBackAfterTheVaultReopens() {
        val changed = DisplaySettings(
            showTotal = true,
            showAssets = false,
            minimumUsd = 0.5,
            hideSpam = false,
            sort = AssetSort(CHANGE_24H, descending = false),
        )
        val first = open()
        DisplaySettingsStore.write(first, changed)
        first.close()

        val reopened = open()
        assertEquals(changed, DisplaySettingsStore.read(reopened))
        reopened.close()
    }

    @Test
    fun aLaterWriteReplacesTheEarlierSettings() {
        val session = open()
        DisplaySettingsStore.write(session, DisplaySettings(showTotal = true))
        DisplaySettingsStore.write(session, DisplaySettings(minimumUsd = 0.0))

        assertEquals(DisplaySettings(minimumUsd = 0.0), DisplaySettingsStore.read(session))
        session.close()
    }

    @Test
    fun aRecordThatDoesNotParseReadsAsTheDefaults() {
        val session = open()
        session.putVaultRecord(DisplaySettingsStore.RECORD, "not json".encodeToByteArray())

        assertEquals(DisplaySettings(), DisplaySettingsStore.read(session))
        session.close()
    }

    @Test
    fun theMinimumIsAnAmountWithAtMostTwoDecimals() {
        assertEquals(1.0, MinimumUsd.parse("1.00")!!, 0.0)
        assertEquals(0.0, MinimumUsd.parse("0")!!, 0.0)
        assertEquals(0.5, MinimumUsd.parse(" .5 ")!!, 0.0)
        assertEquals(2.25, MinimumUsd.parse("2.25")!!, 0.0)
        assertEquals(1.0, MinimumUsd.parse("1.")!!, 0.0)
        assertEquals(1_000_000.0, MinimumUsd.parse("1000000")!!, 0.0)

        assertNull(MinimumUsd.parse(""))
        assertNull(MinimumUsd.parse("-1"))
        assertNull(MinimumUsd.parse("1.005"))
        assertNull(MinimumUsd.parse("1,000"))
        assertNull(MinimumUsd.parse("1e3"))
        assertNull(MinimumUsd.parse("NaN"))
        assertNull(MinimumUsd.parse("1000001"))
    }

    @Test
    fun theMinimumIsShownWithTwoDecimals() {
        assertEquals("1.00", MinimumUsd.format(1.0))
        assertEquals("0.50", MinimumUsd.format(0.5))
        assertEquals("0.00", MinimumUsd.format(0.0))
    }
}
