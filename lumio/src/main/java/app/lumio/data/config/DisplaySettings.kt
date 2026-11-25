package app.lumio.data.config

import app.lensly.core.vault.VaultSession
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * What the wallet screens show, as the owner set it. It is a vault record, so it is encrypted under the vault key. A missing field reads as its default.
 *
 * [showTotal] shows the combined balance of a wallet, and [showAssets] shows each account's balance. The Display page's one Hide Balances switch sets
 * both, so every amount shows or none does. [currency] is the currency that prices and totals show in. [theme] is whether Lumio follows the phone's light
 * or dark setting, or keeps one. [smallBalanceUsd] leaves out a balance worth less than it, and zero shows every balance.
 */
@Serializable
data class DisplaySettings(
    // Always stored: Lumio 1.0.0 left it out for false, so a stored record without it means false (see [DisplaySettingsStore.read]).
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault val showTotal: Boolean = true,
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

    private val json = Json

    /**
     * Reads a record that may be a Lumio 1.0.0 one. That version kept the total hidden by default and left a default out of the record, so a record
     * without [DisplaySettings.showTotal] means the total is hidden.
     */
    private object Stored : JsonTransformingSerializer<DisplaySettings>(DisplaySettings.serializer()) {
        override fun transformDeserialize(element: JsonElement): JsonElement =
            if (element is JsonObject && "showTotal" !in element) JsonObject(element + ("showTotal" to JsonPrimitive(false))) else element
    }

    /**
     * The settings stored in [session], or the defaults when none are stored or the record does not parse. Lumio 1.0.0 had a switch for the total and
     * one for the balances, and now one switch sets both, so a record with either hidden reads with both hidden.
     */
    fun read(session: VaultSession): DisplaySettings {
        val bytes = session.getVaultRecord(RECORD) ?: return DisplaySettings()
        val decoded = try {
            json.decodeFromString(Stored, bytes.decodeToString())
        } catch (e: SerializationException) {
            return DisplaySettings()
        }
        val shown = decoded.showTotal && decoded.showAssets
        val stored = decoded.copy(showTotal = shown, showAssets = shown)
        // A threshold off the slider's stops reads as the default, as an out-of-range Last Chance pause does.
        return stored.takeIf { it.smallBalanceUsd in DisplaySettings.SMALL_BALANCE_STOPS }
            ?: stored.copy(smallBalanceUsd = DisplaySettings.DEFAULT_SMALL_BALANCE_USD)
    }

    fun write(session: VaultSession, settings: DisplaySettings) {
        session.putVaultRecord(RECORD, json.encodeToString(DisplaySettings.serializer(), settings).encodeToByteArray())
    }
}
