package app.lumio.ui.settings

import app.lensly.ui.camera.EntryGesture
import app.lumio.data.config.DisplaySettings

/**
 * What the top-level list shows, as the open vault holds it: the display settings, the chains that are on, whether Bitcoin reads a server the owner set,
 * the data providers that are on out of all of them, and the paired wallets with their saved addresses.
 */
data class SettingsFacts(
    val display: DisplaySettings,
    val chainsOn: Int,
    val customBitcoin: Boolean,
    val providersOn: Int,
    val providersTotal: Int,
    val wallets: Int,
    val addresses: Int,
)

/**
 * The rows of the top-level list, in order, each with the summary of [facts]. [gesture] and [version] complete the Security and About rows. [developer]
 * is the state of the Developer row, which only a debug build has: when it is null, the row is left out, so it is the last row only in a debug build.
 */
fun settingsRows(facts: SettingsFacts, gesture: EntryGesture, version: String, developer: String?): List<SettingsRowState> = listOfNotNull(
    SettingsRowState(SettingsPage.DISPLAY, displaySummary(facts.display)),
    SettingsRowState(SettingsPage.TRANSACTIONS, transactionsSummary(facts.display.hideSpam)),
    SettingsRowState(SettingsPage.NETWORKS, networksSummary(facts.chainsOn, facts.customBitcoin)),
    SettingsRowState(SettingsPage.PROVIDERS, providersSummary(facts.providersOn, facts.providersTotal)),
    SettingsRowState(SettingsPage.ADDRESS_BOOK, addressBookSummary(facts.wallets, facts.addresses)),
    SettingsRowState(SettingsPage.SECURITY, securitySummary(gesture)),
    SettingsRowState(SettingsPage.ABOUT, aboutSummary(version)),
    developer?.let { SettingsRowState(SettingsPage.DEVELOPER, it) },
)
