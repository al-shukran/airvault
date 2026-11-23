package app.lumio.ui.settings

import app.lensly.ui.camera.EntryGesture
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.FinishSetup
import app.lumio.data.config.TransactionSettings

/**
 * What the top-level list shows, as the open vault holds it: the display and transaction settings, the chains that are on, whether Bitcoin reads
 * a server the owner set, the data providers that are on out of all of them, the paired wallets with their saved addresses, and what the Finish
 * Setting Up checklist has ticked.
 */
data class SettingsFacts(
    val display: DisplaySettings,
    val transactions: TransactionSettings,
    val chainsOn: Int,
    val customBitcoin: Boolean,
    val providersOn: Int,
    val providersTotal: Int,
    val wallets: Int,
    val addresses: Int,
    val finish: FinishSetup,
)

/** The rows of the top-level list, in order, each with the summary of [facts]. [gesture] and [version] complete the Security and About rows. */
fun settingsRows(facts: SettingsFacts, gesture: EntryGesture, version: String): List<SettingsRowState> = listOf(
    SettingsRowState(SettingsPage.DISPLAY, displaySummary(facts.display)),
    SettingsRowState(SettingsPage.TRANSACTIONS, transactionsSummary(facts.transactions)),
    SettingsRowState(SettingsPage.WALLETS, walletsSummary(facts.wallets)),
    SettingsRowState(SettingsPage.NETWORKS, networksSummary(facts.chainsOn, facts.customBitcoin)),
    SettingsRowState(SettingsPage.PROVIDERS, providersSummary(facts.providersOn, facts.providersTotal)),
    SettingsRowState(SettingsPage.ADDRESS_BOOK, addressBookSummary(facts.wallets, facts.addresses)),
    SettingsRowState(SettingsPage.SECURITY, securitySummary(gesture)),
    SettingsRowState(SettingsPage.ABOUT, aboutSummary(version)),
)
