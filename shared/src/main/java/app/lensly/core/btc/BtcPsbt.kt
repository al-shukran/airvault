package app.lensly.core.btc

import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.psbt.DataEntry
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.utils.Either
import java.util.Base64

/**
 * PSBT transport (BIP174): the `ur:crypto-psbt` form that QR codes carry, the base64 form, and the Companion App's marker,
 * which is a global proprietary key.
 */
object BtcPsbt {
    /** The global proprietary key the Companion App writes: type 0xFC, the length-prefixed prefix "airvault", subtype 0. */
    private val COMPANION_KEY = ByteVector(
        byteArrayOf(0xFC.toByte(), 0x08) + "airvault".encodeToByteArray() + byteArrayOf(0x00),
    )

    /** The value of the companion marker. */
    private val COMPANION_VALUE = ByteVector("lumio".encodeToByteArray())

    /** [psbt] with the Companion App's marker added. An existing marker is replaced. */
    fun withCompanionMarker(psbt: Psbt): Psbt {
        val others = psbt.global.unknown.filterNot { it.key == COMPANION_KEY }
        return psbt.copy(global = psbt.global.copy(unknown = others + DataEntry(COMPANION_KEY, COMPANION_VALUE)))
    }

    /** True when [psbt] carries the Companion App's marker. */
    fun hasCompanionMarker(psbt: Psbt): Boolean =
        psbt.global.unknown.any { it.key == COMPANION_KEY && it.value == COMPANION_VALUE }

    /**
     * Decodes a PSBT from its `ur:crypto-psbt` form or from base64.
     *
     * @throws IllegalArgumentException when [text] is neither form, or does not parse as a PSBT
     */
    fun decode(text: String): Psbt {
        val trimmed = text.trim()
        val bytes = if (trimmed.startsWith("ur:", ignoreCase = true)) {
            val item = try {
                URDecoder.decode(trimmed).decodeFromRegistry()
            } catch (e: Exception) {
                throw IllegalArgumentException("not a ur:crypto-psbt", e)
            }
            (item as? CryptoPSBT ?: throw IllegalArgumentException("not a ur:crypto-psbt")).psbt
        } else {
            Base64.getDecoder().decode(trimmed)
        }
        return when (val parsed = Psbt.read(bytes)) {
            is Either.Left -> throw IllegalArgumentException("not a valid PSBT: ${parsed.value}")
            is Either.Right -> parsed.value
        }
    }

    /** The `ur:crypto-psbt` form of [psbt]. */
    fun encodeUr(psbt: Psbt): String = UREncoder.encode(CryptoPSBT(Psbt.write(psbt).toByteArray()).toUR())
}
