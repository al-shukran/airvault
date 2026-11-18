package app.lensly.app

import app.lensly.core.btc.BtcFeeThresholds
import app.lensly.core.btc.InputVerificationPolicy
import app.lensly.core.btc.SigningSourcePolicy
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
    /** Which online side may ask for a Bitcoin signature. The default is the core default, ANY. */
    val bitcoinSigningSource: SigningSourcePolicy = SigningSourcePolicy.ANY,
    /** The fee limits that make a Bitcoin review show a WARN notice. The default is the core default, 200 sat/vB and 10 per cent. */
    val bitcoinFeeThresholds: BtcFeeThresholds = BtcFeeThresholds.DEFAULT,
    /** What happens to a call Lensly cannot read. The default is Warn, which reviews the call under an error-level warning. */
    val unknownCallPolicy: UnknownCallPolicy = UnknownCallPolicy.DEFAULT,
)

/**
 * The owner's settings, stored as the vault record `settings` through [VaultSession.putVaultRecord]. The record is a
 * version byte (6), the Bulk Cap as its account count (int), the Proxy Policy code (byte), the shortest and the longest
 * hide delay in milliseconds (two ints), then the Bitcoin input verification code and the Bitcoin signing source code
 * (two bytes), then the Bitcoin fee rate limit in sat/vB and the fee share limit in per cent (two ints), then the unknown-call
 * policy code (a byte), in the same DataOutputStream style as the wallet records.
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
        const val VERSION = 6

        fun encode(settings: AppSettings): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { out ->
                out.writeByte(VERSION)
                out.writeInt(settings.bulkCap.accounts)
                out.writeByte(settings.proxyPolicy.code)
                out.writeInt(settings.hideDelay.minMs.toInt())
                out.writeInt(settings.hideDelay.maxMs.toInt())
                out.writeByte(settings.bitcoinInputPolicy.wire)
                out.writeByte(settings.bitcoinSigningSource.wire)
                out.writeInt(settings.bitcoinFeeThresholds.highFeeRateSatPerVb)
                out.writeInt(settings.bitcoinFeeThresholds.feeSharePercent)
                out.writeByte(settings.unknownCallPolicy.code)
            }
            return buffer.toByteArray()
        }

        fun decode(bytes: ByteArray): AppSettings = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readUnsignedByte() != VERSION) return@use AppSettings()
                // A count the app does not offer reads as the largest cap offered.
                val bulkCap = BulkCap.fromAccounts(input.readInt()) ?: BulkCap.HUNDRED
                // An unknown policy code reads as the default.
                val proxyPolicy = ProxyPolicy.ofCode(input.readUnsignedByte()) ?: ProxyPolicy.DEFAULT
                // A range the app does not accept reads as the default delay.
                val hideDelay = HideDelay.of(input.readInt().toLong(), input.readInt().toLong()) ?: HideDelay.DEFAULT
                // A code this build does not know reads as its core default.
                val bitcoinInputPolicy = InputVerificationPolicy.ofWire(input.readUnsignedByte()) ?: InputVerificationPolicy.STRICT
                val bitcoinSigningSource = SigningSourcePolicy.ofWire(input.readUnsignedByte()) ?: SigningSourcePolicy.ANY
                // Values outside their range read as the core defaults.
                val bitcoinFeeThresholds = BtcFeeThresholds.of(input.readInt(), input.readInt()) ?: BtcFeeThresholds.DEFAULT
                // A code this build does not know reads as the default, Warn.
                val unknownCallPolicy = UnknownCallPolicy.ofCode(input.readUnsignedByte()) ?: UnknownCallPolicy.DEFAULT
                AppSettings(
                    bulkCap = bulkCap,
                    proxyPolicy = proxyPolicy,
                    hideDelay = hideDelay,
                    bitcoinInputPolicy = bitcoinInputPolicy,
                    bitcoinSigningSource = bitcoinSigningSource,
                    bitcoinFeeThresholds = bitcoinFeeThresholds,
                    unknownCallPolicy = unknownCallPolicy,
                )
            }
        } catch (e: IOException) {
            AppSettings()
        }
    }
}
