package app.lumio.data.config

import app.lensly.core.vault.VaultSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What the wallet screens show, as the owner set it. It is a vault record, so it is encrypted under the vault key. A missing field reads as its default.
 *
 * [showTotal] shows the combined balance of a wallet, and [showAssets] shows each account's balance. The Display page's one Hide Balances switch sets
 * both, so every amount shows or none does. [currency] is the currency that prices and totals show in. [theme] is whether Lumio follows the phone's light
 * or dark setting, or keeps one. [smallBalanceUsd] leaves out a balance worth less than it, and zero shows every balance.
 */
@Serializable
data class DisplaySettings(
    val showTotal: Boolean = true,
    val showAssets: Boolean = true,
    val currency: FiatCurrency = FiatCurrency.USD,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val smallBalanceUsd: Double = DEFAULT_SMALL_BALANCE_USD,
) {
    /** Whether any amount is masked. The Hide Balances switch is on while one is. */
    val balancesHidden: Boolean
        get() = !showTotal || !showAssets

    /** Whether a balance worth [usd] dollars is left out of the balance lists. A balance whose value is not known is never left out. */
    fun hidesSmallBalance(usd: Double?): Boolean = usd != null && smallBalanceUsd > 0.0 && usd < smallBalanceUsd

    companion object {
        /** The threshold the owner starts with: a balance worth less than one dollar is left out. */
        const val DEFAULT_SMALL_BALANCE_USD = 1.0

        /** The stops of the Hide Small Balances slider: from $0 to $10 in steps of fifty cents. */
        val SMALL_BALANCE_STOPS: List<Double> = (0..20).map { it * 0.5 }
    }
}

/** The look Lumio takes: [SYSTEM] follows the phone's light or dark setting. [title] names it on the Theme sheet and in the Settings list. */
@Serializable
enum class ThemeMode(val title: String) {
    SYSTEM("Follow System"),
    LIGHT("Light"),
    DARK("Dark"),
}

/** Reads and writes [DisplaySettings] as the vault record [DisplaySettingsStore.RECORD]. */
object DisplaySettingsStore {
    /** The vault record that holds these settings. */
    const val RECORD = "lumio-display-settings"

    // Every field is written, so what a stored record means does not follow the defaults of a later version.
    private val json = Json { encodeDefaults = true }

    /** The settings stored in [session], or the defaults when none are stored or the record does not parse. */
    fun read(session: VaultSession): DisplaySettings = try {
        load(session)
    } catch (e: SerializationException) {
        DisplaySettings()
    }

    /** The settings stored in [session], or the defaults when none are stored. @throws SerializationException when the record does not parse. */
    private fun load(session: VaultSession): DisplaySettings {
        val bytes = session.getVaultRecord(RECORD) ?: return DisplaySettings()
        val stored = json.decodeFromString(DisplaySettings.serializer(), bytes.decodeToString())
        // A threshold off the slider's stops reads as the default, as an out-of-range Last Chance pause does.
        return stored.takeIf { it.smallBalanceUsd in DisplaySettings.SMALL_BALANCE_STOPS }
            ?: stored.copy(smallBalanceUsd = DisplaySettings.DEFAULT_SMALL_BALANCE_USD)
    }

    /**
     * Saves [settings]. A stored record that does not parse is left as it is, and this throws [IllegalStateException], so the defaults that [read]
     * returned in its place never replace the owner's settings.
     */
    fun write(session: VaultSession, settings: DisplaySettings) {
        try {
            load(session)
        } catch (e: SerializationException) {
            throw IllegalStateException("The stored display settings cannot be read, so they are left as they are.", e)
        }
        session.putVaultRecord(RECORD, json.encodeToString(DisplaySettings.serializer(), settings).encodeToByteArray())
    }
}
