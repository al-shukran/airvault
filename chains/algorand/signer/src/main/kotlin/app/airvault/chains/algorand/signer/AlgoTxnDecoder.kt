package app.airvault.chains.algorand.signer

import app.airvault.chains.algorand.AlgoTxn
import app.airvault.chains.algorand.AssetTransfer
import app.airvault.chains.algorand.Payment
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** A transaction that [AlgoTxnDecoder] refused. The message names the field and the rule. */
class AlgoTxnException(message: String) : Exception(message)

/**
 * Decodes an unsigned Algorand transaction and refuses anything outside the canonical encoding or outside this app's
 * review. Only `pay` (payment) and `axfer` (asset transfer, including opt-in) are accepted.
 *
 * Canonical encoding: the Algorand spec, https://specs.algorand.co/crypto/crypto-msgpack ("Canonical Msgpack"), and
 * go-algorand's codec, https://github.com/algorand/go-algorand/blob/master/protocol/codec.go. Keys are unique and in
 * lexicographic order; integers, strings, binary data and maps use their shortest header; no signed or negative
 * integers; zero-valued pairs are omitted. The key order is the byte order of the names, so `fee` sorts before `fv`:
 * go-algorand's codec sorts struct fields by encoded name as a string (`sfiSortedByEncName`, algorand/go-codec v1.1.10,
 * codec/helper.go).
 *
 * Also refused:
 * - a zero or empty value written out, such as `amt` 0, an all-zero address, or an empty `note` or `gen`;
 * - a missing `type`, `snd`, `fv`, `lv` or `gh`, a missing `rcv` (payment), or a missing `xaid` or `arcv` (asset
 *   transfer). go-algorand requires only `snd` and `type` (`required` tags in
 *   https://github.com/algorand/go-algorand/blob/master/data/transactions/transaction.go). The review also needs the
 *   validity window and the network, so the rest are required here;
 * - `fv` after `lv` (`WellFormed`, same file);
 * - a field the transaction type does not use, and any field this decoder does not know;
 * - a length over its bound: `note` at most 1024 bytes (`MaxTxnNoteBytes`), `gen` at most 128 bytes (`MaxGenesisIDLen`,
 *   https://github.com/algorand/go-algorand/blob/master/config/bounds/bounds.go), 32-byte fields exactly 32 bytes, and
 *   the whole transaction at most [MAX_TXN_BYTES]. `gen` and `type` must be valid UTF-8.
 *
 * Omitted optional values decode to zero: `fee`, `amt` and `aamt` are 0, `gen` and `note` are empty, and the other
 * optional fields are null.
 */
object AlgoTxnDecoder {
    /** The largest transaction accepted. The fixed fields, a 1024-byte note and a 128-byte genesis ID total about 1.6 KB. */
    const val MAX_TXN_BYTES = 2048

    private const val MAX_KEY_BYTES = 32
    private const val MAX_NOTE_BYTES = 1024
    private const val MAX_GENESIS_ID_BYTES = 128

    private enum class Kind { UINT, BYTES32, NOTE, TEXT }

    /** Every field this decoder knows, with the value shape it must have. */
    private val FIELD_KINDS: Map<String, Kind> = mapOf(
        "type" to Kind.TEXT, "snd" to Kind.BYTES32, "fee" to Kind.UINT, "fv" to Kind.UINT, "lv" to Kind.UINT,
        "gen" to Kind.TEXT, "gh" to Kind.BYTES32, "note" to Kind.NOTE, "lx" to Kind.BYTES32,
        "grp" to Kind.BYTES32, "rekey" to Kind.BYTES32,
        "rcv" to Kind.BYTES32, "amt" to Kind.UINT, "close" to Kind.BYTES32,
        "xaid" to Kind.UINT, "aamt" to Kind.UINT, "arcv" to Kind.BYTES32, "aclose" to Kind.BYTES32,
        "asnd" to Kind.BYTES32,
    )

    private val COMMON_FIELDS = setOf("type", "snd", "fee", "fv", "lv", "gen", "gh", "note", "lx", "grp", "rekey")
    private val PAYMENT_FIELDS = COMMON_FIELDS + setOf("rcv", "amt", "close")
    private val ASSET_TRANSFER_FIELDS = COMMON_FIELDS + setOf("xaid", "aamt", "arcv", "aclose", "asnd")

    /** Decodes [raw], or throws [AlgoTxnException] with the first rule it breaks. */
    fun decode(raw: ByteArray): AlgoTxn {
        if (raw.size > MAX_TXN_BYTES) {
            throw AlgoTxnException("transaction is ${raw.size} bytes; the limit is $MAX_TXN_BYTES")
        }
        val reader = CanonicalMsgpackReader(raw)
        val count = reader.readMapSize()
        if (count > FIELD_KINDS.size) {
            throw AlgoTxnException("transaction has $count fields, more than the ${FIELD_KINDS.size} this decoder knows")
        }
        val fields = LinkedHashMap<String, Any>(count)
        var previous: ByteArray? = null
        repeat(count) {
            val keyBytes = reader.readStr("key", MAX_KEY_BYTES)
            previous?.let { last ->
                if (lexicographicOrder(last, keyBytes) >= 0) {
                    throw AlgoTxnException("map keys must be unique and in lexicographic order")
                }
            }
            previous = keyBytes
            val key = String(keyBytes, Charsets.UTF_8)
            val kind = FIELD_KINDS[key] ?: throw AlgoTxnException("unsupported field '$key'")
            fields[key] = readValue(reader, key, kind)
        }
        if (!reader.atEnd()) throw AlgoTxnException("bytes follow the transaction map")
        return build(fields)
    }

    private fun readValue(reader: CanonicalMsgpackReader, key: String, kind: Kind): Any = when (kind) {
        Kind.UINT -> reader.readUInt(key).also { if (it == 0UL) throw zero(key) }
        Kind.BYTES32 -> reader.readBin(key, 32).also {
            if (it.isEmpty()) throw zero(key)
            if (it.size != 32) throw AlgoTxnException("'$key' is ${it.size} bytes; it must be 32")
            if (it.all { byte -> byte == 0.toByte() }) throw zero(key)
        }
        Kind.NOTE -> reader.readBin(key, MAX_NOTE_BYTES).also { if (it.isEmpty()) throw zero(key) }
        Kind.TEXT -> reader.readStr(key, MAX_GENESIS_ID_BYTES)
            .also { if (it.isEmpty()) throw zero(key) }
            .let { utf8(it, key) }
    }

    private fun build(f: Map<String, Any>): AlgoTxn {
        val type = f["type"] as? String ?: throw missing("type")
        val allowed = when (type) {
            "pay" -> PAYMENT_FIELDS
            "axfer" -> ASSET_TRANSFER_FIELDS
            else -> throw AlgoTxnException("transaction type '$type' cannot be reviewed; only pay and axfer can")
        }
        f.keys.firstOrNull { it !in allowed }?.let {
            throw AlgoTxnException("field '$it' is not used by $type transactions")
        }
        val firstValid = f.requiredUInt("fv")
        val lastValid = f.requiredUInt("lv")
        if (firstValid > lastValid) {
            throw AlgoTxnException("first valid round $firstValid is after last valid round $lastValid")
        }
        val sender = f.requiredBytes("snd")
        val genesisHash = f.requiredBytes("gh")
        val fee = f.uint("fee") ?: 0UL
        val genesisId = f.text("gen") ?: ""
        val note = f.bytes("note") ?: ByteArray(0)
        val lease = f.bytes("lx")
        val group = f.bytes("grp")
        val rekeyTo = f.bytes("rekey")
        return if (type == "pay") {
            Payment(
                sender = sender, fee = fee, firstValid = firstValid, lastValid = lastValid,
                genesisId = genesisId, genesisHash = genesisHash, note = note, lease = lease, group = group,
                rekeyTo = rekeyTo,
                receiver = f.requiredBytes("rcv"), amount = f.uint("amt") ?: 0UL, closeTo = f.bytes("close"),
            )
        } else {
            AssetTransfer(
                sender = sender, fee = fee, firstValid = firstValid, lastValid = lastValid,
                genesisId = genesisId, genesisHash = genesisHash, note = note, lease = lease, group = group,
                rekeyTo = rekeyTo,
                assetId = f.requiredUInt("xaid"), amount = f.uint("aamt") ?: 0UL,
                receiver = f.requiredBytes("arcv"), closeTo = f.bytes("aclose"), assetSender = f.bytes("asnd"),
            )
        }
    }

    private fun Map<String, Any>.bytes(key: String): ByteArray? = this[key] as ByteArray?
    private fun Map<String, Any>.uint(key: String): ULong? = this[key] as ULong?
    private fun Map<String, Any>.text(key: String): String? = this[key] as String?
    private fun Map<String, Any>.requiredBytes(key: String): ByteArray = bytes(key) ?: throw missing(key)
    private fun Map<String, Any>.requiredUInt(key: String): ULong = uint(key) ?: throw missing(key)

    private fun missing(key: String) = AlgoTxnException("missing required field '$key'")

    private fun zero(key: String) = AlgoTxnException("'$key' is zero or empty; canonical msgpack leaves it out")

    private fun utf8(bytes: ByteArray, key: String): String =
        try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw AlgoTxnException("'$key' is not valid UTF-8")
        }
}
