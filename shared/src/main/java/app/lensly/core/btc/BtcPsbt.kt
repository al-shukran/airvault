package app.lensly.core.btc

import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.framesOf
import com.sparrowwallet.hummingbird.UR
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
        if (trimmed.startsWith("ur:", ignoreCase = true)) {
            val ur = try {
                URDecoder.decode(trimmed)
            } catch (e: Exception) {
                throw IllegalArgumentException("not a ur:crypto-psbt", e)
            }
            return fromUr(ur)
        }
        return parse(Base64.getDecoder().decode(trimmed))
    }

    /**
     * The PSBT that a complete `ur:crypto-psbt` carries. The Parser Sandbox reads a scanned request this way.
     *
     * @throws IllegalArgumentException when [ur] is not a crypto-psbt, or does not parse as a PSBT
     */
    fun fromUr(ur: UR): Psbt {
        val item = try {
            ur.decodeFromRegistry()
        } catch (e: Exception) {
            throw IllegalArgumentException("not a ur:crypto-psbt", e)
        }
        return parse((item as? CryptoPSBT ?: throw IllegalArgumentException("not a ur:crypto-psbt")).psbt)
    }

    /**
     * The PSBT in its serialized (BIP174) form. The Parser Sandbox parses a scanned PSBT before the app uses it, so this reads bytes
     * the sandbox has already accepted.
     *
     * @throws IllegalArgumentException when [bytes] does not parse as a PSBT
     */
    fun parse(bytes: ByteArray): Psbt = when (val parsed = Psbt.read(bytes)) {
        is Either.Left -> throw IllegalArgumentException("not a valid PSBT: ${parsed.value}")
        is Either.Right -> parsed.value
    }

    /** The `ur:crypto-psbt` form of [psbt]. */
    fun encodeUr(psbt: Psbt): String = UREncoder.encode(ur(psbt))

    /**
     * The frames of the `ur:crypto-psbt` that carries [psbt]. A PSBT that fits one QR code is one frame. A larger one gives the
     * uncoded parts of an animated code, so a caller that cycles this list sends the whole PSBT in every pass.
     */
    fun encodeUrParts(psbt: Psbt): List<String> {
        val frames = framesOf(ur(psbt), DEFAULT_MAX_FRAGMENT)
        return frames.take(sequenceLength(frames.first())).toList()
    }

    /** The UR type of a PSBT, as a scanned UR names it. */
    const val UR_TYPE = "crypto-psbt"

    private fun ur(psbt: Psbt): UR = CryptoPSBT(Psbt.write(psbt).toByteArray()).toUR()
}
