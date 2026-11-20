package app.airvault.chains.bitcoin.signer

import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.framesOf
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.DataEntry
import fr.acinq.bitcoin.psbt.Input
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
     * The input-map entry that the online side writes on an input its indexer flags as carrying an inscription or a rune (spec 5.5). It is a
     * BIP174 proprietary key (type 0xFC) with the prefix "airvault", subtype 1 and no key data, and its value is the one byte 0x01.
     */
    val RISK_MARK = DataEntry(
        ByteVector(byteArrayOf(0xFC.toByte(), 0x08) + "airvault".encodeToByteArray() + byteArrayOf(0x01)),
        ByteVector(byteArrayOf(0x01)),
    )

    /**
     * True when [input] carries [RISK_MARK], once or more. Any other entry is not a mark. A repeated mark is still a mark: the input is then
     * marked, so a malformed PSBT can only add a warning, never hide one. Refusing the PSBT instead would also be safe, but it would change the
     * parse for no gain in what the owner sees.
     */
    fun isRiskMarked(input: Input): Boolean = input.unknown.any { it == RISK_MARK }

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
     * The PSBT that a complete `ur:crypto-psbt` carries. The Bitcoin parser reads a scanned request this way.
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
     * The PSBT in its serialized (BIP174) form. The Bitcoin parser checks a scanned PSBT before the app uses it, so this reads bytes
     * the parser has already accepted.
     *
     * @throws IllegalArgumentException when [bytes] does not parse as a PSBT
     */
    fun parse(bytes: ByteArray): Psbt = when (val parsed = Psbt.read(bytes)) {
        is Either.Left -> throw IllegalArgumentException("not a valid PSBT: ${parsed.value}")
        is Either.Right -> parsed.value
    }

    /** The `ur:crypto-psbt` form of [psbt]. */
    fun encodeUr(psbt: Psbt): String = UREncoder.encode(ur(psbt))

    /** The CBOR payload of the `crypto-psbt` that carries [psbt]. A sign response frames it as a UR of [UR_TYPE]. */
    fun cborOf(psbt: Psbt): ByteArray = ur(psbt).cborBytes

    /**
     * The frames of the `ur:crypto-psbt` that carries [psbt]. A PSBT that fits one QR code is one frame. A larger one gives the
     * uncoded parts of an animated code, so a caller that cycles this list sends the whole PSBT in every pass.
     */
    fun encodeUrParts(psbt: Psbt): List<String> {
        val frames = framesOf(ur(psbt), DEFAULT_MAX_FRAGMENT)
        return frames.take(sequenceLength(frames.first())).toList()
    }

    /**
     * Finalizes the inputs of [psbt] that Lensly signed, and extracts the transaction that a node accepts. An input that is already final is
     * kept. An open input must carry exactly one signature, the one its key made, and it must spend a P2PKH, P2WPKH, P2SH-P2WPKH or P2TR output.
     * The result must pass bitcoin-kmp's script interpreter for every input, so a signature that does not verify is refused here, before
     * anything is broadcast.
     *
     * @throws IllegalArgumentException when an input has no previous output, has no signature, spends another script, or does not verify
     */
    fun finalizeAndExtract(psbt: Psbt): Transaction {
        val prevouts = psbt.inputs.mapIndexed { index, input ->
            input.spentOutput() ?: throw IllegalArgumentException("Input $index has no previous output.")
        }
        var finished = psbt
        psbt.inputs.forEachIndexed { index, input ->
            if (input.isOpen()) finished = finished.finalizeInput(index, prevouts[index])
        }
        val tx = when (val extracted = finished.extract()) {
            is Either.Left -> throw IllegalArgumentException("The signed request is incomplete: ${extracted.value}")
            is Either.Right -> extracted.value
        }
        require(verifies(tx, prevouts)) { "A signature in this transaction does not verify." }
        return tx
    }

    private fun Input.spentOutput(): TxOut? = when (this) {
        is Input.WitnessInput.PartiallySignedWitnessInput -> txOut
        is Input.WitnessInput.FinalizedWitnessInput -> txOut
        is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> inputTx.txOut[outputIndex]
        is Input.NonWitnessInput.FinalizedNonWitnessInput -> inputTx.txOut[outputIndex]
        else -> null
    }

    private fun Input.isOpen(): Boolean =
        this is Input.WitnessInput.PartiallySignedWitnessInput || this is Input.NonWitnessInput.PartiallySignedNonWitnessInput

    private fun Psbt.finalizeInput(index: Int, prevout: TxOut): Psbt {
        val script = prevout.publicKeyScript.toByteArray()
        val result = when (val input = inputs[index]) {
            is Input.WitnessInput.PartiallySignedWitnessInput -> {
                val taproot = input.taprootKeySignature
                if (taproot != null) {
                    require(Script.isPay2tr(script)) { "Input $index carries a taproot signature, but it does not spend a taproot output." }
                    finalizeWitnessInput(index, ScriptWitness(listOf(taproot)))
                } else {
                    val (key, signature) = onlySignature(index, input.partialSigs)
                    val nested = Script.isPay2sh(script) && input.redeemScript?.let { Script.isPay2wpkh(it) } == true
                    require(Script.isPay2wpkh(script) || nested) { "Input $index spends a script that this app cannot finalize." }
                    finalizeWitnessInput(index, ScriptWitness(listOf(signature, key.value)))
                }
            }
            is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> {
                require(Script.isPay2pkh(script)) { "Input $index spends a script that this app cannot finalize." }
                val (key, signature) = onlySignature(index, input.partialSigs)
                finalizeNonWitnessInput(index, listOf(OP_PUSHDATA(signature), OP_PUSHDATA(key.value)))
            }
            else -> return this
        }
        return when (result) {
            is Either.Left -> throw IllegalArgumentException("Input $index could not be finalized: ${result.value}")
            is Either.Right -> result.value
        }
    }

    /** The one signature of an input, with the key that made it. An input with none, or with several, is refused. */
    private fun onlySignature(index: Int, signatures: Map<PublicKey, ByteVector>): Pair<PublicKey, ByteVector> {
        val entry = signatures.entries.singleOrNull()
            ?: throw IllegalArgumentException("Input $index has ${if (signatures.isEmpty()) "no signature" else "more than one signature"}.")
        return entry.key to entry.value
    }

    private fun verifies(tx: Transaction, prevouts: List<TxOut>): Boolean = tx.txIn.indices.all { index ->
        runCatching {
            Script.Runner(
                Script.Context(tx, index, prevouts[index].amount, prevouts),
                ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS,
            ).verifyScripts(tx.txIn[index].signatureScript, prevouts[index].publicKeyScript, tx.txIn[index].witness)
        }.getOrDefault(false)
    }

    /** The UR type of a PSBT, as a scanned UR names it. This is the retired name, and Lumio still writes its requests under it. */
    const val UR_TYPE = "crypto-psbt"

    /** The registry's current name for a PSBT. A request may use it, and its response uses the name the request used. */
    const val PSBT_UR_TYPE = "psbt"

    /** The UR types a PSBT request may carry. */
    val REQUEST_UR_TYPES = setOf(PSBT_UR_TYPE, UR_TYPE)

    /**
     * The body of a request, which only the Bitcoin app reads: one length byte, the UR type the request used, then the PSBT in its serialized
     * form. The response is framed under the same name, so the body keeps it.
     */
    fun requestBody(urType: String, psbt: ByteArray): ByteArray =
        byteArrayOf(urType.length.toByte()) + urType.toByteArray(Charsets.US_ASCII) + psbt

    /** The UR type that a request body carries. */
    fun requestUrType(body: ByteArray): String = String(body, 1, body[0].toInt(), Charsets.US_ASCII)

    /** The PSBT, in its serialized form, that a request body carries. */
    fun requestPsbt(body: ByteArray): ByteArray = body.copyOfRange(1 + body[0].toInt(), body.size)

    /** The "i-n" sequence header that every frame of a multi-part UR carries after its type, as in ur:type/1-3/body. */
    private val SEQUENCE_HEADER = Regex("""^UR:[A-Z0-9-]+/\d+-(\d+)/""", RegexOption.IGNORE_CASE)

    /** The number of parts an animated UR has, read from the sequence header of one of its frames, or 1 for a single-part UR. */
    private fun sequenceLength(frame: String): Int = SEQUENCE_HEADER.find(frame)?.groupValues?.get(1)?.toInt() ?: 1

    private fun ur(psbt: Psbt): UR = CryptoPSBT(Psbt.write(psbt).toByteArray()).toUR()
}
