package app.lumio.data.config

import app.lensly.core.vault.VaultSession
import java.util.Locale
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the asset list is sorted by. [CHANGE_24H] is the change over 24 hours, in percent. */
@Serializable
enum class AssetSortKey { USD_VALUE, NAME, BALANCE, CHANGE_24H }

/** The sort of the asset list: its [key], and whether it runs [descending]. */
@Serializable
data class AssetSort(val key: AssetSortKey = AssetSortKey.USD_VALUE, val descending: Boolean = true)

/**
 * What Home shows, as the owner set it. It is a vault record, so it is encrypted under the vault key. A missing field reads as its default.
 *
 * [showTotal] shows the combined balance, and it is off until the owner turns it on. [showAssets] shows each asset's balance. [minimumUsd] is the
 * least value in USD that an asset needs to be shown on Home. An asset with no price, such as a testnet coin, is never hidden by it. [hideSpam]
 * hides transactions that look like spam or scams. [sort] is the order of the asset list.
 */
@Serializable
data class DisplaySettings(
    val showTotal: Boolean = false,
    val showAssets: Boolean = true,
    val minimumUsd: Double = DEFAULT_MINIMUM_USD,
    val hideSpam: Boolean = true,
    val sort: AssetSort = AssetSort(),
) {
    companion object {
        const val DEFAULT_MINIMUM_USD = 1.0

        /** The largest minimum the owner can enter, in USD. */
        const val MAX_MINIMUM_USD = 1_000_000.0
    }
}

/** Reads and writes [DisplaySettings] as the vault record [DisplaySettingsStore.RECORD]. */
object DisplaySettingsStore {
    /** The vault record that holds these settings. */
    const val RECORD = "lumio-display-settings"

    private val json = Json { ignoreUnknownKeys = true }

    /** The settings stored in [session], or the defaults when none are stored or the record does not parse. */
    fun read(session: VaultSession): DisplaySettings {
        val bytes = session.getVaultRecord(RECORD) ?: return DisplaySettings()
        return try {
            json.decodeFromString(DisplaySettings.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            DisplaySettings()
        }
    }

    fun write(session: VaultSession, settings: DisplaySettings) {
        session.putVaultRecord(RECORD, json.encodeToString(DisplaySettings.serializer(), settings).encodeToByteArray())
    }
}

/** The owner's entry for the minimum balance: a dollar amount with at most two decimals, from 0 to [DisplaySettings.MAX_MINIMUM_USD]. */
object MinimumUsd {
    private val ENTRY = Regex("""\d+(\.\d{0,2})?|\.\d{1,2}""")

    /** The amount [text] names, or null when it is not an amount in range. Spaces around the number are ignored. */
    fun parse(text: String): Double? {
        val entry = text.trim()
        if (!ENTRY.matches(entry)) return null
        return entry.toDouble().takeIf { it <= DisplaySettings.MAX_MINIMUM_USD }
    }

    /** The text of [amount] as the field shows it: two decimals, always a point. */
    fun format(amount: Double): String = "%.2f".format(Locale.ROOT, amount)
}
