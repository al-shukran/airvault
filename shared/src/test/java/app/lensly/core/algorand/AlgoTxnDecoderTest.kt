package app.lensly.core.algorand

import app.lensly.core.wallet.toHex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [AlgoTxnDecoder] and [bytesToSign].
 *
 * The valid and refused encodings in `vectors/algorand-txn/vectors.json` were written by py-algorand-sdk 2.12.0 with
 * msgpack 1.2.4 (see the README in that folder). The cases that need a particular encoding, such as a bound or one
 * non-canonical header, are built with [canonical] and the helpers below. [paymentWith] and [assetWith] are first checked
 * against the SDK's bytes in "the test encoder reproduces the SDK bytes".
 */
class AlgoTxnDecoderTest {

    @Test
    fun `vectors were written by the recorded py-algorand-sdk version`() {
        assertEquals("py-algorand-sdk 2.12.0", AlgoTxnVectors.sdkVersion)
    }

    @Test
    fun `payment fixture decodes to the fields the SDK encoded`() = assertDecodesTo("pay_basic")

    @Test
    fun `payment with a note and a close-to address decodes to the SDK fields`() = assertDecodesTo("pay_note_close")

    @Test
    fun `asset transfer fixture decodes to the SDK fields`() = assertDecodesTo("axfer")

    @Test
    fun `asset opt-in fixture decodes to the SDK fields`() = assertDecodesTo("axfer_optin")

    @Test
    fun `rekey payment fixture decodes to the SDK fields`() = assertDecodesTo("pay_rekey")

    @Test
    fun `asset transfer with all sixteen keys decodes to the SDK fields`() = assertDecodesTo("axfer_full_16_keys")

    @Test
    fun `opt-out by close-out decodes to the SDK fields`() = assertDecodesTo("axfer_closeout")

    @Test
    fun `opt-out whose remaining holding goes to another address decodes to the SDK fields`() =
        assertDecodesTo("axfer_optout_to_other")

    @Test
    fun `asset transfer that also closes the holding decodes to the SDK fields`() = assertDecodesTo("axfer_close_with_amount")

    @Test
    fun `clawback fixture decodes to the SDK fields`() = assertDecodesTo("axfer_clawback")

    @Test
    fun `asset rekey fixture decodes to the SDK fields`() = assertDecodesTo("axfer_rekey")

    @Test
    fun `mainnet USDC transfer fixture decodes to the SDK fields`() = assertDecodesTo("axfer_mainnet_usdc")

    @Test
    fun `mainnet USDC opt-in fixture decodes to the SDK fields`() = assertDecodesTo("axfer_optin_mainnet_usdc")

    @Test
    fun `transfer of an asset no catalog names decodes to the SDK fields`() = assertDecodesTo("axfer_unknown_asset")

    @Test
    fun `an opt-in and an opt-out are told apart by the amount and the close-to`() {
        fun asset(name: String) = AlgoTxnDecoder.decode(vector(name).raw) as AssetTransfer

        assertTrue(asset("axfer_optin").isOptIn)
        assertFalse(asset("axfer_optin").isOptOut)
        assertTrue(asset("axfer_optin_mainnet_usdc").isOptIn)
        assertTrue(asset("axfer_closeout").isOptOut)
        assertFalse(asset("axfer_closeout").isOptIn)
        assertTrue(asset("axfer_optout_to_other").isOptOut)
        // A close-to with an amount is a transfer that also closes the holding, not an opt-out.
        assertFalse(asset("axfer_close_with_amount").isOptOut)
        assertFalse(asset("axfer_close_with_amount").isOptIn)
        assertFalse(asset("axfer").isOptIn)
        assertFalse(asset("axfer").isOptOut)
    }

    @Test
    fun `a zero transfer with a close-to to the sender itself is an opt-out, not an opt-in`() {
        val txn = AlgoTxnDecoder.decode(assetWith("arcv" to bin(KEY_A), "aamt" to null, "aclose" to bin(KEY_C))) as AssetTransfer

        assertTrue(txn.isOptOut)
        assertFalse(txn.isOptIn)
        assertContentEquals(KEY_C, txn.closeTo)
    }

    @Test
    fun `opt-in is a transfer of amount zero to the sender`() {
        val txn = AlgoTxnDecoder.decode(vector("axfer_optin").raw) as AssetTransfer
        assertContentEquals(KEY_A, txn.receiver)
        assertEquals(0UL, txn.amount)
        assertNull(txn.assetSender)
    }

    @Test
    fun `rekey target is read from the rekey field`() {
        val txn = AlgoTxnDecoder.decode(vector("pay_rekey").raw) as Payment
        assertContentEquals(KEY_C, txn.rekeyTo)
        assertEquals(0UL, txn.amount)
    }

    @Test
    fun `clawback names the account the assets are taken from`() {
        val txn = AlgoTxnDecoder.decode(assetWith("asnd" to bin(KEY_C))) as AssetTransfer
        assertContentEquals(KEY_C, txn.assetSender)
    }

    @Test
    fun `omitted fee and genesis ID decode to zero and empty`() {
        val txn = AlgoTxnDecoder.decode(paymentWith("fee" to null, "gen" to null))
        assertEquals(0UL, txn.fee)
        assertEquals("", txn.genesisId)
    }

    @Test
    fun `bytesToSign equals the SDK's bytes_to_sign for every fixture`() {
        for (v in AlgoTxnVectors.valid()) {
            assertContentEquals(v.bytesToSign, bytesToSign(v.raw), v.name)
        }
    }

    @Test
    fun `bytesToSign is TX followed by the received bytes`() {
        val raw = vector("pay_basic").raw
        assertContentEquals(byteArrayOf(0x54, 0x58) + raw, bytesToSign(raw))
    }

    @Test
    fun `the test encoder reproduces the SDK bytes`() {
        assertContentEquals(vector("pay_basic").raw, canonical(paymentFields()))
        assertContentEquals(vector("axfer").raw, canonical(assetFields()))
        assertContentEquals(
            vector("pay_note_close").raw,
            paymentWith("amt" to uint(2_000_000_000), "close" to bin(KEY_C), "note" to bin("rent for March".toByteArray())),
        )
    }

    @Test
    fun `unsorted keys from the SDK are refused`() {
        assertRefused(AlgoTxnVectors.refused("unsorted_keys"), "must be unique and in lexicographic order")
    }

    @Test
    fun `application call from the SDK is refused`() {
        assertRefused(AlgoTxnVectors.refused("appl_noop"), "unsupported field 'apid'")
    }

    @Test
    fun `refused SDK encodings with a zero value are refused`() {
        assertRefused(AlgoTxnVectors.refused("zero_amount_present"), "'amt' is zero or empty")
        assertRefused(AlgoTxnVectors.refused("zero_address_present"), "'close' is zero or empty")
        assertRefused(AlgoTxnVectors.refused("empty_note_present"), "'note' is zero or empty")
    }

    @Test
    fun `a repeated key is refused`() {
        val sorted = paymentFields().toList().sortedBy { it.first }
        val repeated = sorted.flatMap { if (it.first == "fee") listOf(it, it) else listOf(it) }
        assertRefused(mapBytes(repeated), "must be unique and in lexicographic order")
    }

    @Test
    fun `an application call with only header fields is refused by its type`() {
        assertRefused(paymentWith("type" to str("appl"), "rcv" to null, "amt" to null), "transaction type 'appl'")
    }

    @Test
    fun `transaction types other than pay and axfer are refused`() {
        for (type in listOf("keyreg", "acfg", "afrz", "stpf", "hb")) {
            assertRefused(paymentWith("type" to str(type)), "transaction type '$type' cannot be reviewed")
        }
    }

    @Test
    fun `integers that are not in the shortest encoding are refused`() {
        assertRefused(paymentWith("fee" to byteArrayOf(0xcc.toByte(), 5)), "'fee' is not canonical")
        assertRefused(paymentWith("fee" to byteArrayOf(0xcd.toByte(), 0, 0xff.toByte())), "'fee' is not canonical")
        assertRefused(
            paymentWith("fv" to byteArrayOf(0xce.toByte(), 0, 0, 0xff.toByte(), 0xff.toByte())),
            "'fv' is not canonical",
        )
        assertRefused(
            paymentWith("amt" to byteArrayOf(0xcf.toByte()) + ByteArray(4) + ByteArray(4) { 0xff.toByte() }),
            "'amt' is not canonical",
        )
    }

    @Test
    fun `strings, binary data and maps with a longer header than needed are refused`() {
        assertRefused(
            paymentWith("gen" to byteArrayOf(0xd9.toByte(), 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())),
            "'gen' is not canonical",
        )
        assertRefused(paymentWith("snd" to byteArrayOf(0xc5.toByte(), 0, 32) + KEY_A), "'snd' is not canonical")
        val sorted = paymentFields().toList().sortedBy { it.first }
        assertRefused(byteArrayOf(0xde.toByte(), 0, sorted.size.toByte()) + encodedEntries(sorted), "map is not canonical")
    }

    @Test
    fun `signed, negative, float, boolean and array values are refused`() {
        assertRefused(paymentWith("fee" to byteArrayOf(0xd0.toByte(), 5)), "'fee' must be an unsigned integer")
        assertRefused(paymentWith("fee" to byteArrayOf(0xff.toByte())), "'fee' must be an unsigned integer")
        assertRefused(paymentWith("fee" to byteArrayOf(0xcb.toByte()) + ByteArray(8)), "'fee' must be an unsigned integer")
        assertRefused(paymentWith("amt" to byteArrayOf(0xc3.toByte())), "'amt' must be an unsigned integer")
        assertRefused(paymentWith("fee" to byteArrayOf(0x91.toByte(), 5)), "'fee' must be an unsigned integer")
    }

    @Test
    fun `zero and empty values written out are refused`() {
        assertRefused(paymentWith("amt" to uint(0)), "'amt' is zero or empty")
        assertRefused(paymentWith("fee" to uint(0)), "'fee' is zero or empty")
        assertRefused(paymentWith("rcv" to bin(ByteArray(32))), "'rcv' is zero or empty")
        assertRefused(paymentWith("close" to bin(ByteArray(32))), "'close' is zero or empty")
        assertRefused(paymentWith("note" to bin(ByteArray(0))), "'note' is zero or empty")
        assertRefused(paymentWith("gen" to str("")), "'gen' is zero or empty")
        assertRefused(assetWith("aamt" to uint(0)), "'aamt' is zero or empty")
        assertRefused(assetWith("arcv" to bin(ByteArray(32))), "'arcv' is zero or empty")
    }

    @Test
    fun `missing required fields are refused`() {
        assertRefused(paymentWith("type" to null), "missing required field 'type'")
        assertRefused(paymentWith("snd" to null), "missing required field 'snd'")
        assertRefused(paymentWith("fv" to null), "missing required field 'fv'")
        assertRefused(paymentWith("lv" to null), "missing required field 'lv'")
        assertRefused(paymentWith("gh" to null), "missing required field 'gh'")
        assertRefused(paymentWith("rcv" to null), "missing required field 'rcv'")
        assertRefused(assetWith("xaid" to null), "missing required field 'xaid'")
        assertRefused(assetWith("arcv" to null), "missing required field 'arcv'")
    }

    @Test
    fun `fields that another transaction type uses are refused`() {
        assertRefused(paymentWith("aamt" to uint(5)), "field 'aamt' is not used by pay transactions")
        assertRefused(assetWith("amt" to uint(5)), "field 'amt' is not used by axfer transactions")
    }

    @Test
    fun `unknown fields are refused`() {
        assertRefused(paymentWith("foo" to str("bar")), "unsupported field 'foo'")
    }

    @Test
    fun `genesis ID must be valid UTF-8`() {
        assertRefused(paymentWith("gen" to byteArrayOf(0xa2.toByte(), 0xff.toByte(), 0x61)), "'gen' is not valid UTF-8")
    }

    @Test
    fun `a map with more fields than the decoder knows is refused`() {
        assertRefused(byteArrayOf(0xde.toByte(), 0, 20), "transaction has 20 fields")
    }

    @Test
    fun `bytes after the map and a cut-off value are refused`() {
        val raw = vector("pay_basic").raw
        assertRefused(raw + byteArrayOf(0), "bytes follow the transaction map")
        assertRefused(raw.copyOf(raw.size - 1), "ends in the middle of a value")
    }

    @Test
    fun `input over the transaction size limit is refused before parsing`() {
        assertRefused(ByteArray(AlgoTxnDecoder.MAX_TXN_BYTES + 1), "transaction is 2049 bytes")
    }

    @Test
    fun `note of 1024 bytes is accepted and 1025 is refused`() {
        val accepted = AlgoTxnDecoder.decode(paymentWith("note" to bin(ByteArray(1024) { 0x41 })))
        assertEquals(1024, (accepted as Payment).note.size)
        assertRefused(paymentWith("note" to bin(ByteArray(1025) { 0x41 })), "'note' is 1025 bytes; the limit is 1024")
    }

    @Test
    fun `genesis ID of 128 bytes is accepted and 129 is refused`() {
        val accepted = AlgoTxnDecoder.decode(paymentWith("gen" to str("g".repeat(128))))
        assertEquals(128, (accepted as Payment).genesisId.length)
        assertRefused(paymentWith("gen" to str("g".repeat(129))), "'gen' is 129 bytes; the limit is 128")
    }

    @Test
    fun `32-byte fields must be exactly 32 bytes`() {
        assertRefused(paymentWith("rekey" to bin(ByteArray(31) { 1 })), "'rekey' is 31 bytes; it must be 32")
        assertRefused(paymentWith("lx" to bin(ByteArray(33) { 1 })), "'lx' is 33 bytes; the limit is 32")
    }

    @Test
    fun `first valid round after last valid round is refused`() {
        assertRefused(
            paymentWith("fv" to uint(50_002_000)),
            "first valid round 50002000 is after last valid round 50001000",
        )
    }

    private fun assertDecodesTo(name: String) {
        val v = vector(name)
        assertEquals(v.expected, fieldsOf(AlgoTxnDecoder.decode(v.raw)), name)
    }

    private fun assertRefused(raw: ByteArray, mention: String) {
        val error = assertFailsWith<AlgoTxnException> { AlgoTxnDecoder.decode(raw) }
        assertTrue(checkNotNull(error.message).contains(mention), "expected '$mention' in: ${error.message}")
    }

    private fun vector(name: String): AlgoTxnVectors.Valid = AlgoTxnVectors.valid().single { it.name == name }
}

internal val KEY_A = hexToBytes("7491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538")
internal val KEY_B = hexToBytes("c11dff7e9149041e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615d")
internal val KEY_C = hexToBytes("8d3ed18cb82bd72eab9a1bc21a77eeb097b7fd68f973f706036d219a165d006d")
private val GENESIS_HASH = java.util.Base64.getDecoder().decode("SGO1GKSzyE7IEPItTxCByw9x8FmnrCDexi9/cOUJOiI=")

/** The decoded fields in the shape of a vector's `expected` object. */
private fun fieldsOf(txn: AlgoTxn): JsonObject = buildJsonObject {
    put("type", if (txn is Payment) "pay" else "axfer")
    put("sender", txn.sender.toHex())
    put("fee", txn.fee.toLong())
    put("firstValid", txn.firstValid.toLong())
    put("lastValid", txn.lastValid.toLong())
    put("genesisId", txn.genesisId)
    put("genesisHash", txn.genesisHash.toHex())
    put("note", txn.note.toHex())
    put("lease", txn.lease?.toHex())
    put("group", txn.group?.toHex())
    put("rekeyTo", txn.rekeyTo?.toHex())
    when (txn) {
        is Payment -> {
            put("receiver", txn.receiver.toHex())
            put("amount", txn.amount.toLong())
            put("closeTo", txn.closeTo?.toHex())
        }
        is AssetTransfer -> {
            put("assetId", txn.assetId.toLong())
            put("amount", txn.amount.toLong())
            put("receiver", txn.receiver.toHex())
            put("closeTo", txn.closeTo?.toHex())
            put("assetSender", txn.assetSender?.toHex())
        }
    }
}

private fun paymentFields(): MutableMap<String, ByteArray> = linkedMapOf(
    "type" to str("pay"),
    "snd" to bin(KEY_A),
    "fee" to uint(1_000),
    "fv" to uint(50_000_000),
    "lv" to uint(50_001_000),
    "gen" to str("testnet-v1.0"),
    "gh" to bin(GENESIS_HASH),
    "rcv" to bin(KEY_B),
    "amt" to uint(1_500_000),
)

private fun assetFields(): MutableMap<String, ByteArray> = linkedMapOf(
    "type" to str("axfer"),
    "snd" to bin(KEY_A),
    "fee" to uint(1_000),
    "fv" to uint(50_000_000),
    "lv" to uint(50_001_000),
    "gen" to str("testnet-v1.0"),
    "gh" to bin(GENESIS_HASH),
    "xaid" to uint(10_458_941),
    "arcv" to bin(KEY_B),
    "aamt" to uint(250),
)

/** A payment built from the base fields; a null change removes that field. */
internal fun paymentWith(vararg changes: Pair<String, ByteArray?>): ByteArray =
    canonical(applied(paymentFields(), changes))

/** An asset transfer built from the base fields; a null change removes that field. */
internal fun assetWith(vararg changes: Pair<String, ByteArray?>): ByteArray =
    canonical(applied(assetFields(), changes))

private fun applied(
    fields: MutableMap<String, ByteArray>,
    changes: Array<out Pair<String, ByteArray?>>,
): Map<String, ByteArray> {
    for ((key, value) in changes) {
        if (value == null) fields.remove(key) else fields[key] = value
    }
    return fields
}

/** The canonical encoding of a map: keys sorted, shortest map header. */
private fun canonical(fields: Map<String, ByteArray>): ByteArray {
    val sorted = fields.toList().sortedBy { it.first }
    return mapBytes(sorted)
}

/** A map with its entries in the order given and the shortest header for their count. */
private fun mapBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
    val header = if (entries.size < 16) {
        byteArrayOf((0x80 or entries.size).toByte())
    } else {
        byteArrayOf(0xde.toByte()) + be(entries.size.toLong(), 2)
    }
    return header + encodedEntries(entries)
}

private fun encodedEntries(pairs: List<Pair<String, ByteArray>>): ByteArray =
    pairs.fold(ByteArray(0)) { acc, (key, value) -> acc + str(key) + value }

private fun uint(value: Long): ByteArray = when {
    value < 0x80 -> byteArrayOf(value.toByte())
    value < 0x100 -> byteArrayOf(0xcc.toByte()) + be(value, 1)
    value < 0x1_0000 -> byteArrayOf(0xcd.toByte()) + be(value, 2)
    value < 0x1_0000_0000L -> byteArrayOf(0xce.toByte()) + be(value, 4)
    else -> byteArrayOf(0xcf.toByte()) + be(value, 8)
}

internal fun str(text: String): ByteArray {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val header = when {
        bytes.size < 32 -> byteArrayOf((0xa0 or bytes.size).toByte())
        bytes.size < 256 -> byteArrayOf(0xd9.toByte(), bytes.size.toByte())
        else -> byteArrayOf(0xda.toByte()) + be(bytes.size.toLong(), 2)
    }
    return header + bytes
}

internal fun bin(bytes: ByteArray): ByteArray {
    val header = if (bytes.size < 256) {
        byteArrayOf(0xc4.toByte(), bytes.size.toByte())
    } else {
        byteArrayOf(0xc5.toByte()) + be(bytes.size.toLong(), 2)
    }
    return header + bytes
}

private fun be(value: Long, width: Int): ByteArray =
    ByteArray(width) { (value ushr (8 * (width - 1 - it))).toByte() }
