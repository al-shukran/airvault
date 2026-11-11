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
)

/**
 * The owner's settings, stored as the vault record `settings` through [VaultSession.putVaultRecord]. The record is a
 * version byte (2), the Bulk Cap as its account count (int), then the Proxy Policy code (byte), in the same
 * DataOutputStream style as the wallet records. Version 1 had only the Bulk Cap, so it reads with the default policy.
 *
 * A missing record, a damaged one, or an unknown version reads as the default, so a bad setting never blocks the vault.
 * A cap the app no longer offers, such as the retired 1,000, reads as 100, the largest cap offered. A closed session
 * still throws.
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
        const val VERSION = 2

        fun encode(settings: AppSettings): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { out ->
                out.writeByte(VERSION)
                out.writeInt(settings.bulkCap.accounts)
                out.writeByte(settings.proxyPolicy.code)
            }
            return buffer.toByteArray()
        }

        fun decode(bytes: ByteArray): AppSettings = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val version = input.readUnsignedByte()
                if (version != VERSION && version != VERSION_1) return@use AppSettings()
                // A count the app does not offer, such as the retired 1,000, reads as the largest cap now offered.
                val bulkCap = BulkCap.fromAccounts(input.readInt()) ?: BulkCap.HUNDRED
                // An unknown policy code reads as the default, as does every version 1 record.
                val proxyPolicy = if (version == VERSION) {
                    ProxyPolicy.ofCode(input.readUnsignedByte()) ?: ProxyPolicy.DEFAULT
                } else {
                    ProxyPolicy.DEFAULT
                }
                AppSettings(bulkCap = bulkCap, proxyPolicy = proxyPolicy)
            }
        } catch (e: IOException) {
            AppSettings()
        }
    }
}
