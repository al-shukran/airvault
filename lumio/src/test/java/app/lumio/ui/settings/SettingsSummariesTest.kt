package app.lumio.ui.settings

import app.lensly.ui.camera.EntryGesture
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.ProviderConfig
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The one-line state under each row of the top-level list, and the rows that the list shows. */
class SettingsSummariesTest {

    @Test
    fun theDisplayRowNamesTheTotalAndTheBalances() {
        assertEquals("Total Hidden · balances shown", displaySummary(DisplaySettings()))
        assertEquals("Total shown · balances shown", displaySummary(DisplaySettings(showTotal = true)))
    }

    @Test
    fun theTransactionsRowNamesTheCommentBackup() {
        assertEquals("Back up transaction comments", transactionsSummary())
    }

    @Test
    fun theNetworksRowCountsTheChainsAndNamesTheBitcoinServer() {
        assertEquals("1 chain on · Bitcoin default", networksSummary(chainsOn = 1, customBitcoin = false))
        assertEquals("3 chains on · Bitcoin custom server", networksSummary(chainsOn = 3, customBitcoin = true))
    }

    @Test
    fun theProvidersRowCountsTheSourcesThatAreOn() {
        assertEquals("7 of 9 sources on", providersSummary(on = 7, total = 9))
    }

    @Test
    fun theWalletsRowCountsThePairedWallets() {
        assertEquals("No wallets paired", walletsSummary(0))
        assertEquals("1 wallet paired", walletsSummary(1))
        assertEquals("3 wallets paired", walletsSummary(3))
    }

    @Test
    fun theAddressBookRowCountsWalletsAndAddresses() {
        assertEquals("No wallets paired", addressBookSummary(wallets = 0, addresses = 0))
        assertEquals("1 wallet · 1 saved address", addressBookSummary(wallets = 1, addresses = 1))
        assertEquals("2 wallets · 5 saved addresses", addressBookSummary(wallets = 2, addresses = 5))
    }

    @Test
    fun theSecurityRowNamesTheGesture() {
        assertEquals("Entry gesture: Two-finger double tap", securitySummary(EntryGesture.DOUBLE_TAP_HOLD))
        assertEquals("Entry gesture: Thumbnail hold and swipe", securitySummary(EntryGesture.THUMBNAIL_HOLD_SWIPE))
    }

    @Test
    fun theAboutRowNamesTheVersion() {
        assertEquals("Version 0.1.0", aboutSummary("0.1.0"))
    }

    @Test
    fun aProviderWithNoSavedSettingIsOnAsItsDefaultSays() {
        val (on, total) = providerCounts(emptyMap(), debug = false)

        assertTrue(total > 0)
        assertEquals(total, on)
    }

    @Test
    fun aSwitchedOffProviderIsNotCountedAsOn() {
        val (allOn, total) = providerCounts(emptyMap(), debug = false)
        val (on, _) = providerCounts(mapOf("mempool" to ProviderConfig(enabled = false)), debug = false)

        assertEquals(total, allOn)
        assertEquals(allOn - 1, on)
    }

    private val facts = SettingsFacts(
        display = DisplaySettings(),
        chainsOn = 3,
        customBitcoin = false,
        providersOn = 7,
        providersTotal = 9,
        wallets = 1,
        addresses = 2,
    )

    @Test
    fun theListShowsEveryPageInOrderAndLeavesDeveloperOutInRelease() {
        val rows = settingsRows(facts, EntryGesture.DEFAULT, version = "0.1.0")

        assertEquals(
            listOf(
                SettingsPage.DISPLAY,
                SettingsPage.TRANSACTIONS,
                SettingsPage.NETWORKS,
                SettingsPage.PROVIDERS,
                SettingsPage.WALLETS,
                SettingsPage.ADDRESS_BOOK,
                SettingsPage.SECURITY,
                SettingsPage.ABOUT,
            ),
            rows.map { it.page },
        )
    }

    @Test
    fun eachRowCarriesItsSummary() {
        val rows = settingsRows(facts, EntryGesture.DEFAULT, version = "0.1.0").associateBy { it.page }

        assertEquals("Total Hidden · balances shown", rows.getValue(SettingsPage.DISPLAY).summary)
        assertEquals("3 chains on · Bitcoin default", rows.getValue(SettingsPage.NETWORKS).summary)
        assertEquals("7 of 9 sources on", rows.getValue(SettingsPage.PROVIDERS).summary)
        assertEquals("1 wallet · 2 saved addresses", rows.getValue(SettingsPage.ADDRESS_BOOK).summary)
        assertEquals("Version 0.1.0", rows.getValue(SettingsPage.ABOUT).summary)
    }
}
