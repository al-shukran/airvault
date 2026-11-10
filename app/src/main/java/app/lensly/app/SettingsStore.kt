package app.lensly.app

import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** The owner's settings. A vault that has never saved them reads the defaults. */
data class AppSettings(val bulkCap: BulkCap = BulkCap.DEFAULT)

/**
 * The owner's settings, stored as the vault record `settings` through [VaultSession.putVaultRecord]. The record is a
 * version byte (1), then the Bulk Cap as its account count (int), in the same DataOutputStream style as the wallet
 * records.
 *
 * A missing record, a damaged one, an unknown version, or a cap the app does not offer all read as the default, so a
 * bad setting never blocks the vault. The default Bulk Cap is the safe one. A closed session still throws.
 */
class SettingsStore(private val session: VaultSession) {

    /** The saved settings, or the defaults. */
    fun load(): AppSettings = try {
        session.getVaultRecord(NAME)?.let { decode(it) } ?: AppSettings()
    } catch (e: VaultException) {
        AppSettings()
    }

    /** Saves [settings], replacing the earlier record. */
    fun save(settings: AppSettings) {
        session.putVaultRecord(NAME, encode(settings))
    }

    private companion object {
        const val NAME = "settings"
        const val VERSION = 1

        fun encode(settings: AppSettings): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { out ->
                out.writeByte(VERSION)
                out.writeInt(settings.bulkCap.accounts)
            }
            return buffer.toByteArray()
        }

        fun decode(bytes: ByteArray): AppSettings = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readUnsignedByte() != VERSION) return@use AppSettings()
                AppSettings(bulkCap = BulkCap.fromAccounts(input.readInt()) ?: BulkCap.DEFAULT)
            }
        } catch (e: IOException) {
            AppSettings()
        }
    }
}
