package app.lumio.ui.settings

import app.lensly.ui.camera.EntryGesture
import app.lumio.data.config.DisplaySettings
import app.lumio.data.config.ProviderConfig
import app.lumio.data.config.TransactionSettings
import app.lumio.data.config.configOf
import app.lumio.data.provider.ProviderCatalog

/** The one-line state under the Display row: whether the total shows, and whether each account's balance does. */
fun displaySummary(settings: DisplaySettings): String =
    "${if (settings.showTotal) "Total shown" else "Total hidden"} · ${if (settings.showAssets) "balances shown" else "balances hidden"}"

/** The one-line state under the Transactions row: whether spam is hidden, and the Last Chance pause before a send. */
fun transactionsSummary(settings: TransactionSettings): String {
    val spam = if (settings.hideSpam) "Spam hidden" else "Spam shown"
    val pause = if (settings.lastChanceSeconds == 0) "sends at once" else "${settings.lastChanceSeconds} s last chance before sending"
    return "$spam · $pause"
}

/** The one-line state under the Networks row: how many chains are on, and whether Bitcoin reads a server the owner set. */
fun networksSummary(chainsOn: Int, customBitcoin: Boolean): String {
    val chains = if (chainsOn == 1) "1 chain on" else "$chainsOn chains on"
    return "$chains · ${if (customBitcoin) "Bitcoin custom server" else "Bitcoin default"}"
}

/** The one-line state under the Data providers row: how many providers are on, of all of them. */
fun providersSummary(on: Int, total: Int): String = "$on of $total sources on"

/** The one-line state under the Wallets row: how many wallets are paired from Lensly. */
fun walletsSummary(wallets: Int): String = when (wallets) {
    0 -> "No wallets paired"
    1 -> "1 wallet paired"
    else -> "$wallets wallets paired"
}

/** The one-line state under the Address book row: how many wallets are paired, and how many addresses their books hold. */
fun addressBookSummary(wallets: Int, addresses: Int): String {
    if (wallets == 0) return "No wallets paired"
    val walletWord = if (wallets == 1) "1 wallet" else "$wallets wallets"
    val addressWord = if (addresses == 1) "1 saved address" else "$addresses saved addresses"
    return "$walletWord · $addressWord"
}

/** The one-line state under the Security row: the gesture that opens the Hidden Area. */
fun securitySummary(gesture: EntryGesture): String = "Entry gesture: ${shortGestureName(gesture)}"

/** The short name of [gesture] for a summary line. Its full description is on the Security page. */
fun shortGestureName(gesture: EntryGesture): String = when (gesture) {
    EntryGesture.DOUBLE_TAP_HOLD -> "Two-finger double tap"
    EntryGesture.THUMBNAIL_HOLD_SWIPE -> "Thumbnail hold and swipe"
    EntryGesture.PINCH_TRIPLE_TAP -> "Pinch, then triple tap"
    EntryGesture.SWITCH_THEN_SHUTTER_HOLD -> "Switch camera, then shutter hold"
}

/** The one-line state under the About row. */
fun aboutSummary(version: String): String = "Version $version"

/**
 * The providers that are on, and how many providers there are, under the [saved] settings. A provider with no saved setting takes its default, which is on.
 * [debug] is true in a debug build, which also lists the local RPC address.
 */
fun providerCounts(saved: Map<String, ProviderConfig>, debug: Boolean): Pair<Int, Int> {
    val configs = ProviderCatalog.withDefaults(saved)
    val ids = (ProviderCatalog.dataProviders() + ProviderCatalog.priceProviders() + ProviderCatalog.rpcProviders(debug)).map { it.id }
    return ids.count { configOf(configs, it).enabled } to ids.size
}
