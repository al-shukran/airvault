package app.lumio.data.config

import app.lensly.core.vault.VaultSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What the wallet screens show, as the owner set it. It is a vault record, so it is encrypted under the vault key. A missing field reads as its default.
 *
 * [showTotal] shows the combined balance of a wallet, and it is off until the owner turns it on. [showAssets] shows each account's balance.
 * [currency] is the currency that prices and totals show in. [smallBalanceUsd] leaves out a balance worth less than it, and zero shows every balance.
 */
@Serializable
data class DisplaySettings(
    val showTotal: Boolean = false,
    val showAssets: Boolean = true,
    val currency: FiatCurrency = FiatCurrency.USD,
    val smallBalanceUsd: Double = DEFAULT_SMALL_BALANCE_USD,
) {
    /** Whether a balance worth [usd] dollars is left out of the balance lists. A balance whose value is not known is never left out. */
    fun hidesSmallBalance(usd: Double?): Boolean = usd != null && smallBalanceUsd > 0.0 && usd < smallBalanceUsd

    companion object {
        /** The threshold the owner starts with: a balance worth less than one dollar is left out. */
        const val DEFAULT_SMALL_BALANCE_USD = 1.0

        /** The stops of the Hide Small Balances slider: from $0 to $10 in steps of fifty cents. */
        val SMALL_BALANCE_STOPS: List<Double> = (0..20).map { it * 0.5 }
    }
}

/** Reads and writes [DisplaySettings] as the vault record [DisplaySettingsStore.RECORD]. */
object DisplaySettingsStore {
    /** The vault record that holds these settings. */
    const val RECORD = "lumio-display-settings"

    private val json = Json

    /** The settings stored in [session], or the defaults when none are stored or the record does not parse. */
    fun read(session: VaultSession): DisplaySettings {
        val bytes = session.getVaultRecord(RECORD) ?: return DisplaySettings()
        val stored = try {
            json.decodeFromString(DisplaySettings.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            return DisplaySettings()
        }
        // A threshold off the slider's stops reads as the default, as an out-of-range Last Chance pause does.
        return stored.takeIf { it.smallBalanceUsd in DisplaySettings.SMALL_BALANCE_STOPS }
            ?: stored.copy(smallBalanceUsd = DisplaySettings.DEFAULT_SMALL_BALANCE_USD)
    }

    fun write(session: VaultSession, settings: DisplaySettings) {
        session.putVaultRecord(RECORD, json.encodeToString(DisplaySettings.serializer(), settings).encodeToByteArray())
    }
}
