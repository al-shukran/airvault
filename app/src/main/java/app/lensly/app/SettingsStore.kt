package app.lensly.app

import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** The owner's settings. A vault that has never saved them reads the defaults. */
data class AppSettings(
    val bulkCap: BulkCap = BulkCap.DEFAULT,
    val proxyPolicy: ProxyPolicy = ProxyPolicy.DEFAULT,
    val hideDelay: HideDelay = HideDelay.DEFAULT,
)

/**
 * The owner's settings, stored as the vault record `settings` through [VaultSession.putVaultRecord]. The record is a
 * version byte (3), the Bulk Cap as its account count (int), the Proxy Policy code (byte), then the shortest and the
 * longest hide delay in milliseconds (two ints), in the same DataOutputStream style as the wallet records. Version 1
 * had only the Bulk Cap, so it reads with the default policy; version 2 has no hide delay, so it reads with the default.
 *
 * A missing record, a damaged one, or an unknown version reads as the default, so a bad setting never blocks the vault.
 * A cap the app no longer offers, such as the retired 1,000, reads as 100, the largest cap offered. A hide delay outside
 * [HideDelay] reads as the default delay. A closed session still throws.
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
        const val VERSION_1 = 1
        const val VERSION_2 = 2
        const val VERSION = 3

        fun encode(settings: AppSettings): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { out ->
                out.writeByte(VERSION)
                out.writeInt(settings.bulkCap.accounts)
                out.writeByte(settings.proxyPolicy.code)
                out.writeInt(settings.hideDelay.minMs.toInt())
                out.writeInt(settings.hideDelay.maxMs.toInt())
            }
            return buffer.toByteArray()
        }

        fun decode(bytes: ByteArray): AppSettings = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val version = input.readUnsignedByte()
                if (version !in VERSION_1..VERSION) return@use AppSettings()
                // A count the app does not offer, such as the retired 1,000, reads as the largest cap now offered.
                val bulkCap = BulkCap.fromAccounts(input.readInt()) ?: BulkCap.HUNDRED
                // An unknown policy code reads as the default, as does every version 1 record.
                val proxyPolicy = if (version >= VERSION_2) {
                    ProxyPolicy.ofCode(input.readUnsignedByte()) ?: ProxyPolicy.DEFAULT
                } else {
                    ProxyPolicy.DEFAULT
                }
                // A range the app does not accept reads as the default delay. Version 1 and 2 records have none.
                val hideDelay = if (version == VERSION) {
                    HideDelay.of(input.readInt().toLong(), input.readInt().toLong()) ?: HideDelay.DEFAULT
                } else {
                    HideDelay.DEFAULT
                }
                AppSettings(bulkCap = bulkCap, proxyPolicy = proxyPolicy, hideDelay = hideDelay)
            }
        } catch (e: IOException) {
            AppSettings()
        }
    }
}
