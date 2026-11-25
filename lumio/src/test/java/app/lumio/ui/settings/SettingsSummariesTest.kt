package app.lumio.ui.settings

import app.lensly.ui.camera.EntryGesture
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.FiatCurrency
import app.lumio.data.config.FinishSetup
import app.lumio.data.config.ProviderConfig
import app.lumio.data.config.ThemeMode
import app.lumio.data.config.TransactionSettings
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The one-line state under each row of the top-level list, and the rows that the list shows. */
class SettingsSummariesTest {

    @Test
    fun theDisplayRowNamesTheBalancesTheCurrencyAndTheTheme() {
        assertEquals("Balances shown · USD · Follow system theme", displaySummary(DisplaySettings()))
        assertEquals("Balances shown · EUR · Follow system theme", displaySummary(DisplaySettings(currency = FiatCurrency.EUR)))
        assertEquals("Balances hidden · USD · Follow system theme", displaySummary(DisplaySettings(showAssets = false)))
        assertEquals("Balances shown · USD · Dark theme", displaySummary(DisplaySettings(theme = ThemeMode.DARK)))
    }

    @Test
    fun theTransactionsRowNamesTheSpamAndTheLastChancePause() {
        assertEquals("Spam hidden · 60 s last chance before sending", transactionsSummary(TransactionSettings()))
        assertEquals("Spam shown · 10 s last chance before sending", transactionsSummary(TransactionSettings(hideSpam = false, lastChanceSeconds = 10)))
        assertEquals("Spam hidden · sends at once", transactionsSummary(TransactionSettings(lastChanceSeconds = 0)))
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
    fun aProviderThatNeedsAKeyIsOffUntilOneIsSaved() {
        val (on, total) = providerCounts(emptyMap(), debug = false)

        // Etherscan, Blockchair and Ankr need a key, so they start switched off. The other providers start switched on.
        assertTrue(total > 0)
        assertEquals(total - 3, on)
    }

    @Test
    fun aSwitchedOffProviderIsNotCountedAsOn() {
        val (allOn, _) = providerCounts(emptyMap(), debug = false)
        val (on, _) = providerCounts(mapOf("mempool" to ProviderConfig(enabled = false)), debug = false)

        assertEquals(allOn - 1, on)
    }

    private val facts = SettingsFacts(
        display = DisplaySettings(),
        transactions = TransactionSettings(),
        chainsOn = 3,
        customBitcoin = false,
        providersOn = 7,
        providersTotal = 9,
        wallets = 1,
        addresses = 2,
        finish = FinishSetup(),
    )

    @Test
    fun theListShowsEveryPageInOrderAndLeavesDeveloperOutInRelease() {
        val rows = settingsRows(facts, EntryGesture.DEFAULT, version = "0.1.0")

        assertEquals(
            listOf(
                SettingsPage.DISPLAY,
                SettingsPage.TRANSACTIONS,
                SettingsPage.WALLETS,
                SettingsPage.NETWORKS,
                SettingsPage.PROVIDERS,
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

        assertEquals("Balances shown · USD · Follow system theme", rows.getValue(SettingsPage.DISPLAY).summary)
        assertEquals("3 chains on · Bitcoin default", rows.getValue(SettingsPage.NETWORKS).summary)
        assertEquals("7 of 9 sources on", rows.getValue(SettingsPage.PROVIDERS).summary)
        assertEquals("1 wallet · 2 saved addresses", rows.getValue(SettingsPage.ADDRESS_BOOK).summary)
        assertEquals("Version 0.1.0", rows.getValue(SettingsPage.ABOUT).summary)
    }
}
