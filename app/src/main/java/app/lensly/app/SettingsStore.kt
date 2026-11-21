package app.lensly.app

import app.airvault.chains.bitcoin.signer.BtcFeeThresholds
import app.airvault.chains.bitcoin.signer.InputVerificationPolicy
import app.airvault.chains.bitcoin.signer.SIGNING_SOURCE_SETTING
import app.airvault.chains.bitcoin.signer.SigningSourcePolicy
import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.registry.UnknownCallPolicy
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
    /** How Bitcoin input amounts are verified. The default is the core default, STRICT. */
    val bitcoinInputPolicy: InputVerificationPolicy = InputVerificationPolicy.STRICT,
    /**
     * The settings that chain apps declare, by the key each app names them with, as the code the owner chose. A key that is absent reads as
     * the app's default: Bitcoin's signing source is [SIGNING_SOURCE_SETTING], and reads as ANY when it is absent.
     */
    val appSettings: Map<String, Int> = emptyMap(),
    /** The fee limits that make a Bitcoin review show a WARN notice. The default is the core default, 200 sat/vB and 10 per cent. */
    val bitcoinFeeThresholds: BtcFeeThresholds = BtcFeeThresholds.DEFAULT,
    /** What happens to a call Lensly cannot read. The default is Warn, which reviews the call under an error-level warning. */
    val unknownCallPolicy: UnknownCallPolicy = UnknownCallPolicy.DEFAULT,
) {
    /** Which online side may ask for a Bitcoin signature: the owner's choice, or the core default, ANY. */
    fun bitcoinSigningSource(): SigningSourcePolicy =
        appSettings[SIGNING_SOURCE_SETTING]?.let { SigningSourcePolicy.ofWire(it) } ?: SigningSourcePolicy.ANY

    /** These settings with the Bitcoin signing source set to [source]. */
    fun withBitcoinSigningSource(source: SigningSourcePolicy): AppSettings = copy(appSettings = appSettings + (SIGNING_SOURCE_SETTING to source.wire))
}

/**
 * The owner's settings, stored as the vault record `settings` through [VaultSession.putVaultRecord]. The record is a version byte (7), the Bulk
 * Cap as its account count (int), the Proxy Policy code (byte), the shortest and the longest hide delay in milliseconds (two ints), then the
 * Bitcoin input verification code (a byte), then the app settings: their count (a byte), and each one as its key (UTF text) and its code (int),
 * then the Bitcoin fee rate limit in sat/vB and the fee share limit in per cent (two ints), then the unknown-call policy code (a byte), in the
 * same DataOutputStream style as the wallet records.
 *
 * A missing record, a damaged one, or an unknown version reads as the default, so a bad setting never blocks the vault.
 * A cap the app does not offer reads as 100, the largest cap offered. A hide delay outside
 * [HideDelay] reads as the default delay. A Bitcoin policy code this version does not know reads as its core default. Fee
 * limits outside the range [BtcFeeThresholds.of] accepts read as the core defaults.
 * A closed session still throws.
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
        const val VERSION = 7

        /** The most app settings a record holds. An app declares a few, so a larger count is damage. */
        const val MAX_APP_SETTINGS = 16

        fun encode(settings: AppSettings): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { out ->
                out.writeByte(VERSION)
                out.writeInt(settings.bulkCap.accounts)
                out.writeByte(settings.proxyPolicy.code)
                out.writeInt(settings.hideDelay.minMs.toInt())
                out.writeInt(settings.hideDelay.maxMs.toInt())
                out.writeByte(settings.bitcoinInputPolicy.wire)
                out.writeByte(settings.appSettings.size)
                for ((key, code) in settings.appSettings) {
                    out.writeUTF(key)
                    out.writeInt(code)
                }
                out.writeInt(settings.bitcoinFeeThresholds.highFeeRateSatPerVb)
                out.writeInt(settings.bitcoinFeeThresholds.feeSharePercent)
                out.writeByte(settings.unknownCallPolicy.code)
            }
            return buffer.toByteArray()
        }

        fun decode(bytes: ByteArray): AppSettings = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                // A record of another version is not read. It resets to the defaults, as a damaged record does.
                if (input.readUnsignedByte() != VERSION) return@use AppSettings()
                // A count the app does not offer reads as the largest cap offered.
                val bulkCap = BulkCap.fromAccounts(input.readInt()) ?: BulkCap.HUNDRED
                // An unknown policy code reads as the default.
                val proxyPolicy = ProxyPolicy.ofCode(input.readUnsignedByte()) ?: ProxyPolicy.DEFAULT
                // A range the app does not accept reads as the default delay.
                val hideDelay = HideDelay.of(input.readInt().toLong(), input.readInt().toLong()) ?: HideDelay.DEFAULT
                // A code this build does not know reads as its core default.
                val bitcoinInputPolicy = InputVerificationPolicy.ofWire(input.readUnsignedByte()) ?: InputVerificationPolicy.STRICT
                val appSettings = decodeAppSettings(input)
                // Values outside their range read as the core defaults.
                val bitcoinFeeThresholds = BtcFeeThresholds.of(input.readInt(), input.readInt()) ?: BtcFeeThresholds.DEFAULT
                // A code this build does not know reads as the default, Warn.
                val unknownCallPolicy = UnknownCallPolicy.ofCode(input.readUnsignedByte()) ?: UnknownCallPolicy.DEFAULT
                AppSettings(
                    bulkCap = bulkCap,
                    proxyPolicy = proxyPolicy,
                    hideDelay = hideDelay,
                    bitcoinInputPolicy = bitcoinInputPolicy,
                    appSettings = appSettings,
                    bitcoinFeeThresholds = bitcoinFeeThresholds,
                    unknownCallPolicy = unknownCallPolicy,
                )
            }
        } catch (e: IOException) {
            AppSettings()
        }

        /** The app settings of a record: a count, then each setting's key and code. A count above the bound reads as damage. */
        private fun decodeAppSettings(input: DataInputStream): Map<String, Int> {
            val count = input.readUnsignedByte()
            if (count > MAX_APP_SETTINGS) throw IOException("too many app settings")
            return List(count) { input.readUTF() to input.readInt() }.toMap()
        }
    }
}
