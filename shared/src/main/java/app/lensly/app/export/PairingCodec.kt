
package app.lensly.app.export

import app.airvault.chains.evm.evmAddressOf
import app.airvault.core.model.AddressKind
import app.airvault.core.model.ChainId
import app.airvault.core.model.Curve
import app.airvault.core.signer.AccountExport
import app.airvault.core.signer.PublicKey
import app.airvault.core.signer.SignerRef
import app.lensly.app.AddressBookEntry
import app.lensly.app.autoAccountName
import app.lensly.core.parse.DecodeBudget
import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.framesOf
import com.sparrowwallet.hummingbird.UR
import java.nio.ByteBuffer
import java.util.UUID

/**
 * One account of the pairing: its [export] (spec 7.5), and the names Lensly shows for it. [name] is the Display Name, or the Auto-Name when the
 * owner gave the account none. [label] and [group] are null when there is none.
 */
data class PairingAccount(
    val export: AccountExport,
    val name: String,
    val label: String?,
    val group: String?,
)

/**
 * The pairing of one wallet: its master fingerprint, its name, the base of the Auto-Name of its accounts, the accounts of every chain it holds,
 * and the Address Book that Lensly sends with it. [autoNameBase] is the base of the Auto-Name that [autoAccountName] builds for an account, so an
 * account named by its Auto-Name is sent with no name, and the reader builds the name from the base and the index. [addressBook] is empty when
 * the owner saved no entries, and a code without entries carries no book.
 */
data class PairingPayload(
    val fingerprint: Long,
    val name: String,
    val autoNameBase: String,
    val accounts: List<PairingAccount>,
    val addressBook: List<AddressBookEntry> = emptyList(),
)

/** Compresses the body of a pairing. Lensly passes Google's Brotli encoder. */
fun interface BrotliCompressor {
    fun compress(bytes: ByteArray): ByteArray
}

/**
 * Decompresses the body of a pairing. It stops at [maxBytes], and it throws IllegalArgumentException for a body that would be
 * larger, so a small code cannot make its reader hold a large body. Lumio passes its Brotli decoder.
 */
fun interface BrotliDecompressor {
    fun decompress(bytes: ByteArray, maxBytes: Int): ByteArray
}

/**
 * The AirVault pairing: the code Lensly shows for a wallet, one for all of its accounts on every chain, so the online companion imports every
 * account and its names at once. The code is a `ur:x-airvault-pairing`, animated when it is long. Its CBOR is versioned: a reader refuses a
 * version it does not know, so it never reads a newer layout as its own, and it does not read an older one. The payload holds public data
 * only: keys, chain codes, paths, chains and names. The codec does not know the chains: each account is its [AccountExport].
 *
 * The layout, with integer keys. The code's CBOR is an envelope:
 *  - envelope: { 1: version (7), 6: encoding, 7: body (bytes) }. Encoding 0 keeps the body as it is, and encoding 2 holds it Brotli-compressed.
 *  - body, once it is decompressed: { 2: fingerprint (uint32), 3: name, 4: auto-name base (may be empty), 5: [run],
 *    6: [label or group] (optional: absent when no account has a label or group), 7: [address] (optional: absent when the Address Book is empty) }
 *  - run: a block of consecutive accounts of one chain, whose keys have one curve, one kind, one path shape and one key length, and whose
 *    indices are consecutive: { 1: chain (CAIP-2 text), 2: curve (0 secp256k1, 1 Ed25519 SLIP-10, 2 Ed25519 BIP32), 3: kind (0 default),
 *    4: purpose, 5: coin type, 6: first account index, 7: count, 8: [suffix] (the path after the index, not hardened), 9: keys (bytes,
 *    count × key length), 10: chain codes (bytes, count × 32, optional), 11: [name] (optional: absent when every account is named by its
 *    Auto-Name), 12: [label index] (optional), 13: [group index] (optional) }. The path of account i of a run is m/purpose'/coin'/i'/suffix.
 *    A name of "" is the Auto-Name: the auto-name base, a space and the account index. A label or group index of 0 is none, and n is entry n-1 of key 6.
 *  - key length: 33 bytes for a secp256k1 key (compressed), or 32 bytes for an Ed25519 key, with no prefix.
 *  - address: { 1: id, 2: address, 3: name, 4: label (optional) }, one Address Book entry. An id that is a lower-case UUID is 16 bytes, and an
 *    address that is an EVM address in checksum form is 20 bytes. Any other id or address is text.
 *
 * The body is at most [MAX_BODY_BYTES] bytes, compressed or not. The encoder keeps it compressed only when that makes it smaller.
 */
object PairingCodec {
    /** The UR type of the pairing. Any other type is refused. */
    const val UR_TYPE = "x-airvault-pairing"

    /**
     * The layout version of this build, which it writes and reads. A code of any other version is refused with a message that says why. There is
     * no reader for an older version: an older code must be paired again.
     */
    const val VERSION = 7L

    /**
     * The largest CBOR fragment of one animated frame, in bytes. It is the only frame size of the pairing, so a change of it is one edit here.
     * A larger fragment means fewer frames, and the scanner reads the fragments of any size.
     */
    const val FRAGMENT_BYTES = 300

    /** The largest body in bytes, compressed or not. A reader refuses a larger body, so a body it decompresses is bounded by this. */
    const val MAX_BODY_BYTES = 512 * 1024

    /** The most accounts one pairing carries, on all chains together. */
    const val MAX_ACCOUNTS = 512

    /** The most Bitcoin accounts a wallet keeps, in Lensly and in Lumio: the largest Bulk Cap, which the Bitcoin record is sized for. */
    const val MAX_BITCOIN_ACCOUNTS = 100

    /** The most components a key's path has after its account index. */
    const val MAX_SUFFIX = 8

    /**
     * The longest wallet or account name, and the longest label or group, in UTF-8 bytes. A reader stores each within the slot of a
     * record, so a name that is longer is refused, and a label or group of 16 characters at four bytes each still fits.
     */
    const val MAX_NAME_BYTES = 96
    const val MAX_LABEL_BYTES = 64

    /** The most distinct labels and groups one pairing carries, which is what its label and group indexes count. */
    const val MAX_DICTIONARY = 1024

    /** The most Address Book entries one pairing carries. Lensly sends the first of them, and the owner is told when it leaves the rest out. */
    const val MAX_ADDRESS_BOOK = 500

    /** The longest entry id and address, and the longest entry name, in UTF-8 bytes: a UUID, an address, and 32 characters at four bytes. */
    const val MAX_ID_BYTES = 64
    const val MAX_ADDRESS_BYTES = 64
    const val MAX_ENTRY_NAME_BYTES = 128

    private const val NOT_A_PAIRING = "This is not a pairing code from Lensly."
    private const val UINT32_MAX = 0xFFFFFFFFL
    private const val INDEX_MASK = 0x7FFFFFFFL
    private const val CHAIN_CODE_BYTES = 32
    private const val UUID_BYTES = 16
    private const val EVM_ADDRESS_BYTES = 20
    private const val COMPRESSED_KEY_BYTES = 33
    private const val ED25519_KEY_BYTES = 32
    private const val MAX_CHAIN_BYTES = 64
    private const val ENCODING_RAW = 0L
    private const val ENCODING_BROTLI = 2L
    private const val SECP256K1_CODE = 0L
    private const val ED25519_SLIP10_CODE = 1L
    private const val ED25519_BIP32_CODE = 2L
    private const val DEFAULT_KIND_CODE = 0L

    /**
     * The frames of the pairing [payload]: one code when it fits, otherwise the uncoded parts of an animated code. [brotli] compresses
     * the body. Null stores the body as it is, which a reader of this version also reads.
     */
    fun urParts(payload: PairingPayload, brotli: BrotliCompressor?): List<String> =
        partsOf(framesOf(UR(UR_TYPE, encode(payload, brotli)), FRAGMENT_BYTES))

    /**
     * The pairing that [ur] carries. [brotli] decompresses a compressed body, and null refuses such a body.
     * @throws IllegalArgumentException with a message the owner can read, when [ur] is not a pairing, holds an unknown version, or breaks the layout.
     */
    fun fromUr(ur: UR, brotli: BrotliDecompressor?): PairingPayload {
        require(ur.type == UR_TYPE) { NOT_A_PAIRING }
        return decode(ur.cborBytes, brotli)
    }

    /**
     * The CBOR bytes of [payload], which must be valid. The body is compressed with [brotli] when that makes it smaller, and stored as it
     * is otherwise. Null stores it as it is.
     */
    fun encode(payload: PairingPayload, brotli: BrotliCompressor?): ByteArray {
        validate(payload)
        val body = Cbor.encode(Cbor.Dict(bodyFields(payload)))
        require(body.size <= MAX_BODY_BYTES) { "The pairing is larger than $MAX_BODY_BYTES bytes." }
        val packed = brotli?.compress(body)
        val (encoding, stored) = if (packed != null && packed.size < body.size) ENCODING_BROTLI to packed else ENCODING_RAW to body
        return Cbor.encode(
            Cbor.Dict(listOf(uint(1) to uint(VERSION), uint(6) to uint(encoding), uint(7) to Cbor.Bytes(stored))),
        )
    }

    /**
     * The pairing that [bytes] encode. The version is read first, so a newer or older layout is refused by its version and not by its fields.
     * [brotli] decompresses a compressed body, and null refuses one.
     * @throws IllegalArgumentException with a message the owner can read.
     */
    fun decode(bytes: ByteArray, brotli: BrotliDecompressor?): PairingPayload {
        val root = try {
            Cbor.decode(bytes, DecodeBudget("CBOR", ParserLimits.MAX_PAIRING_ITEMS))
        } catch (e: Exception) {
            throw IllegalArgumentException(NOT_A_PAIRING, e)
        }
        // The version is read before any other field, so a layout of another version is refused by its version.
        val version = (root as? Cbor.Dict)?.entries
            ?.firstOrNull { (key, _) -> (key as? Cbor.Uint)?.value == 1L }
            ?.let { (_, value) -> (value as? Cbor.Uint)?.value }
            ?: refuse()
        if (version != VERSION) {
            throw IllegalArgumentException(
                "This pairing uses format $version, and this app reads format $VERSION. Update the app, then pair again.",
            )
        }
        return try {
            payloadOf(bodyFieldsOf(root, brotli))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(NOT_A_PAIRING, e)
        }
    }

    /** The fields 2 to 7 of the body, read from its envelope [root] and decompressed with [brotli] when it is compressed. */
    private fun bodyFieldsOf(root: Cbor.Dict, brotli: BrotliDecompressor?): Map<Long, Cbor> {
        val envelope = fieldsOf(root, 1L..7L)
        if (envelope.keys != setOf(1L, 6L, 7L)) refuse()
        val stored = byteString(required(envelope, 7L), MAX_BODY_BYTES)
        val body = when (uintValue(required(envelope, 6L), ENCODING_BROTLI)) {
            ENCODING_RAW -> stored
            ENCODING_BROTLI -> try {
                (brotli ?: refuse()).decompress(stored, MAX_BODY_BYTES)
            } catch (e: IllegalArgumentException) {
                refuse()
            }
            else -> refuse()
        }
        if (body.size > MAX_BODY_BYTES) refuse()
        val decoded = try {
            Cbor.decode(body, DecodeBudget("CBOR", ParserLimits.MAX_PAIRING_ITEMS))
        } catch (e: Exception) {
            refuse()
        }
        return fieldsOf(decoded as? Cbor.Dict ?: refuse(), 2L..7L)
    }

    /** The pairing of the fields 2 to 7 of a body. */
    private fun payloadOf(fields: Map<Long, Cbor>): PairingPayload {
        val fingerprint = uintValue(required(fields, 2L), UINT32_MAX)
        val autoNameBase = textOf(required(fields, 4L), 0, MAX_NAME_BYTES)
        val dictionary = fields[6L]?.let { items(it, MAX_DICTIONARY).map { item -> textOf(item, 1, MAX_LABEL_BYTES) } } ?: emptyList()
        val payload = PairingPayload(
            fingerprint = fingerprint,
            name = textOf(required(fields, 3L), 1, MAX_NAME_BYTES),
            autoNameBase = autoNameBase,
            accounts = items(required(fields, 5L), MAX_ACCOUNTS).flatMap { decodeRun(it, fingerprint, autoNameBase, dictionary) },
            addressBook = fields[7L]?.let { book -> items(book, MAX_ADDRESS_BOOK).map { decodeAddress(it) } } ?: emptyList(),
        )
        validate(payload)
        return payload
    }

    /** The fields of a body, in key order. A body carries no version of its own: the envelope does. */
    private fun bodyFields(payload: PairingPayload): List<Pair<Cbor, Cbor>> {
        val dictionary = payload.accounts.flatMap { listOfNotNull(it.label, it.group) }.distinct()
        val indexes = dictionary.withIndex().associate { (index, text) -> text to index + 1 }
        val fields = mutableListOf(
            uint(2) to uint(payload.fingerprint),
            uint(3) to Cbor.Text(payload.name),
            uint(4) to Cbor.Text(payload.autoNameBase),
            uint(5) to Cbor.Arr(runsOf(payload.accounts).map { encodeRun(it, payload.autoNameBase, indexes) }),
        )
        // Each of these is left out when it is empty, so a code that has none of them carries no key 6 or 7.
        if (dictionary.isNotEmpty()) fields += uint(6) to Cbor.Arr(dictionary.map { Cbor.Text(it) })
        if (payload.addressBook.isNotEmpty()) fields += uint(7) to Cbor.Arr(payload.addressBook.map { encodeAddress(it) })
        return fields
    }

    /** The accounts of [accounts] in runs: each run is consecutive accounts that [continues] one another. */
    private fun runsOf(accounts: List<PairingAccount>): List<List<PairingAccount>> {
        val runs = mutableListOf<MutableList<PairingAccount>>()
        for (account in accounts) {
            val last = runs.lastOrNull()
            if (last != null && continues(last, account)) last += account else runs += mutableListOf(account)
        }
        return runs
    }

    /**
     * True when [account] extends [run]: it is the next account index of the same chain, with the same curve, kind, path shape, key length and
     * chain-code shape, so the run can state each of them once.
     */
    private fun continues(run: List<PairingAccount>, account: PairingAccount): Boolean {
        val head = run[0].export
        val next = account.export
        return next.chain == head.chain &&
            next.publicKey.curve == head.publicKey.curve &&
            next.kind == head.kind &&
            purposeOf(next) == purposeOf(head) &&
            coinOf(next) == coinOf(head) &&
            suffixOf(next) == suffixOf(head) &&
            next.publicKey.bytes.size == head.publicKey.bytes.size &&
            (next.chainCode == null) == (head.chainCode == null) &&
            indexOf(next) == indexOf(head) + run.size
    }

    private fun encodeRun(run: List<PairingAccount>, autoNameBase: String, indexes: Map<String, Int>): Cbor {
        val head = run[0].export
        val first = indexOf(head)
        val fields = mutableListOf(
            uint(1) to Cbor.Text(head.chain.caip2),
            uint(2) to uint(curveCode(head.publicKey.curve)),
            uint(3) to uint(kindCode(head.kind)),
            uint(4) to uint(purposeOf(head)),
            uint(5) to uint(coinOf(head)),
            uint(6) to uint(first),
            uint(7) to uint(run.size.toLong()),
            uint(8) to Cbor.Arr(suffixOf(head).map { uint(it.toLong()) }),
            uint(9) to Cbor.Bytes(concat(run.map { it.export.publicKey.bytes })),
        )
        if (head.chainCode != null) fields += uint(10) to Cbor.Bytes(concat(run.map { it.export.chainCode!! }))
        val names = run.mapIndexed { position, account ->
            if (account.name == autoAccountName(autoNameBase, (first + position).toInt())) "" else account.name
        }
        // The names are left out when every account is named by its Auto-Name, and the labels and groups when none has one.
        if (names.any { it.isNotEmpty() }) fields += uint(11) to Cbor.Arr(names.map { Cbor.Text(it) })
        if (run.any { it.label != null }) fields += uint(12) to Cbor.Arr(run.map { uint(indexOf(it.label, indexes)) })
        if (run.any { it.group != null }) fields += uint(13) to Cbor.Arr(run.map { uint(indexOf(it.group, indexes)) })
        return Cbor.Dict(fields)
    }

    /** The accounts of one run, whose accounts are numbered from the run's first index. */
    private fun decodeRun(item: Cbor, fingerprint: Long, autoNameBase: String, dictionary: List<String>): List<PairingAccount> {
        val fields = fieldsOf(item as? Cbor.Dict ?: refuse(), 1L..13L)
        val chain = ChainId(textOf(required(fields, 1L), 1, MAX_CHAIN_BYTES))
        val curve = curveOf(uintValue(required(fields, 2L), ED25519_BIP32_CODE))
        val kind = kindOf(uintValue(required(fields, 3L), DEFAULT_KIND_CODE))
        val purpose = uintValue(required(fields, 4L), INDEX_MASK).toInt()
        val coin = uintValue(required(fields, 5L), INDEX_MASK).toInt()
        val first = uintValue(required(fields, 6L), INDEX_MASK)
        val count = uintValue(required(fields, 7L), MAX_ACCOUNTS.toLong()).toInt()
        if (count < 1 || first + count - 1 > INDEX_MASK) refuse()
        val suffix = items(required(fields, 8L), MAX_SUFFIX).map { uintValue(it, INDEX_MASK).toInt() }
        val material = (required(fields, 9L) as? Cbor.Bytes)?.value ?: refuse()
        if (material.size % count != 0 || material.size / count != keyBytesOf(curve)) refuse()
        val length = keyBytesOf(curve)
        val chainCodes = fields[10L]?.let { value ->
            val bytes = (value as? Cbor.Bytes)?.value ?: refuse()
            if (bytes.size != count * CHAIN_CODE_BYTES) refuse()
            bytes
        }
        val names = fields[11L]?.let { value -> column(value, count).map { textOf(it, 0, MAX_NAME_BYTES) } }
        val labels = fields[12L]?.let { value -> column(value, count).map { labelOf(it, dictionary) } }
        val groups = fields[13L]?.let { value -> column(value, count).map { labelOf(it, dictionary) } }
        return List(count) { position ->
            val index = (first + position).toInt()
            // An empty name is the Auto-Name, and so is every name of a run that has no names column.
            val explicit = names?.get(position).orEmpty()
            PairingAccount(
                export = AccountExport(
                    chain = chain,
                    signer = SignerRef(
                        fingerprint = fingerprint.toInt(),
                        path = listOf(PathStep(purpose, true), PathStep(coin, true), PathStep(index, true)) +
                            suffix.map { PathStep(it, false) },
                    ),
                    publicKey = PublicKey(curve, material.copyOfRange(position * length, (position + 1) * length)),
                    chainCode = chainCodes?.copyOfRange(position * CHAIN_CODE_BYTES, (position + 1) * CHAIN_CODE_BYTES),
                    kind = kind,
                ),
                name = if (explicit.isEmpty()) autoAccountName(autoNameBase, index) else explicit,
                label = labels?.get(position),
                group = groups?.get(position),
            )
        }
    }

    /** The label or group an index names: 0 is none, and n is entry n-1 of the dictionary. */
    private fun labelOf(item: Cbor, dictionary: List<String>): String? {
        val index = uintValue(item, dictionary.size.toLong())
        return if (index == 0L) null else dictionary[(index - 1).toInt()]
    }

    /** The index of a label or group in the dictionary, plus one, and 0 for none. */
    private fun indexOf(text: String?, indexes: Map<String, Int>): Long = if (text == null) 0L else indexes.getValue(text).toLong()

    /** The items of a run's column, which holds exactly [count] items: one for each account of the run. */
    private fun column(item: Cbor, count: Int): List<Cbor> = items(item, count).also { if (it.size != count) refuse() }

    /** The purpose, coin type and account index of an account's path, with the hardened bit left out. */
    private fun purposeOf(export: AccountExport): Long = export.signer.path[0].index.toLong()

    private fun coinOf(export: AccountExport): Long = export.signer.path[1].index.toLong()

    private fun indexOf(export: AccountExport): Long = export.signer.path[2].index.toLong()

    /** The path after the account index, which the run states once. */
    private fun suffixOf(export: AccountExport): List<Int> = export.signer.path.drop(3).map { it.index }

    private fun curveCode(curve: Curve): Long = when (curve) {
        Curve.SECP256K1 -> SECP256K1_CODE
        Curve.ED25519_SLIP10 -> ED25519_SLIP10_CODE
        Curve.ED25519_BIP32 -> ED25519_BIP32_CODE
    }

    private fun curveOf(code: Long): Curve = when (code) {
        SECP256K1_CODE -> Curve.SECP256K1
        ED25519_SLIP10_CODE -> Curve.ED25519_SLIP10
        ED25519_BIP32_CODE -> Curve.ED25519_BIP32
        else -> refuse()
    }

    /** The length of a key on [curve]: a compressed secp256k1 key, or an Ed25519 key with no prefix. */
    private fun keyBytesOf(curve: Curve): Int = if (curve == Curve.SECP256K1) COMPRESSED_KEY_BYTES else ED25519_KEY_BYTES

    private fun kindCode(kind: AddressKind): Long = when (kind) {
        AddressKind.DEFAULT -> DEFAULT_KIND_CODE
    }

    private fun kindOf(code: Long): AddressKind = when (code) {
        DEFAULT_KIND_CODE -> AddressKind.DEFAULT
        else -> refuse()
    }

    /** The Address Book entry of one address item: { 1: id, 2: address, 3: name, 4: label (optional) }. */
    private fun encodeAddress(entry: AddressBookEntry): Cbor {
        val entries = mutableListOf<Pair<Cbor, Cbor>>(
            uint(1) to entryIdItem(entry.id),
            uint(2) to entryAddressItem(entry.address),
            uint(3) to Cbor.Text(entry.name),
        )
        entry.label?.let { entries += uint(4) to Cbor.Text(it) }
        return Cbor.Dict(entries)
    }

    /** A lower-case UUID is packed as its 16 bytes. Any other id stays text, so the id that is read back is the one that was written. */
    private fun entryIdItem(id: String): Cbor {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull()
        if (uuid == null || uuid.toString() != id) return Cbor.Text(id)
        val bytes = ByteBuffer.allocate(UUID_BYTES).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        return Cbor.Bytes(bytes)
    }

    /** An EVM address in checksum form is packed as its 20 bytes. Any other address stays text. */
    private fun entryAddressItem(address: String): Cbor {
        if (evmAddressOf(address) != address) return Cbor.Text(address)
        return Cbor.Bytes(ByteArray(EVM_ADDRESS_BYTES) { i -> address.substring(2 + 2 * i, 4 + 2 * i).toInt(16).toByte() })
    }

    private fun decodeAddress(item: Cbor): AddressBookEntry {
        val fields = fieldsOf(item as? Cbor.Dict ?: refuse(), 1L..4L)
        return AddressBookEntry(
            id = entryId(required(fields, 1L)),
            address = entryAddress(required(fields, 2L)),
            name = textOf(required(fields, 3L), 1, MAX_ENTRY_NAME_BYTES),
            label = fields[4L]?.let { textOf(it, 1, MAX_LABEL_BYTES) },
        )
    }

    /** An entry's id: a packed id is the 16 bytes of a UUID, and any other id is text. */
    private fun entryId(item: Cbor): String {
        val bytes = (item as? Cbor.Bytes)?.value ?: return textOf(item, 1, MAX_ID_BYTES)
        if (bytes.size != UUID_BYTES) refuse()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }

    /** An entry's address: a packed address is the 20 bytes of an EVM address, in checksum form, and any other address is text. */
    private fun entryAddress(item: Cbor): String {
        val bytes = (item as? Cbor.Bytes)?.value ?: return textOf(item, 1, MAX_ADDRESS_BYTES)
        if (bytes.size != EVM_ADDRESS_BYTES) refuse()
        val hex = bytes.joinToString("") { byte -> "%02x".format(byte) }
        return evmAddressOf("0x$hex") ?: refuse()
    }

    /** Checks the invariants of [payload], so an encoder never writes a code a reader would refuse. */
    private fun validate(payload: PairingPayload) {
        require(payload.fingerprint in 0..UINT32_MAX) { "The fingerprint is a uint32." }
        require(isPlain(payload.name, 1, MAX_NAME_BYTES)) { "A name is 1 to $MAX_NAME_BYTES bytes of plain text." }
        require(isPlain(payload.autoNameBase, 0, MAX_NAME_BYTES)) { "The auto-name base is up to $MAX_NAME_BYTES bytes of plain text." }
        require(payload.accounts.size <= MAX_ACCOUNTS) { "A pairing carries at most $MAX_ACCOUNTS accounts." }
        for (account in payload.accounts) {
            val export = account.export
            require(export.signer.fingerprint == payload.fingerprint.toInt()) { "An account's key is the wallet's master key." }
            val path = export.signer.path
            require(path.size in 3..3 + MAX_SUFFIX && path.take(3).all { it.hardened && it.index >= 0 }) {
                "A key path is m/purpose'/coin'/account' with up to $MAX_SUFFIX components after it."
            }
            require(path.drop(3).all { !it.hardened && it.index >= 0 }) { "A key path's suffix is not hardened." }
            require(export.publicKey.bytes.size == keyBytesOf(export.publicKey.curve)) {
                "A key is 33 bytes (compressed secp256k1) or 32 bytes (Ed25519)."
            }
            val chainCode = export.chainCode
            require(chainCode == null || chainCode.size == CHAIN_CODE_BYTES) { "A key's chain code is 32 bytes." }
            require(isPlain(account.name, 1, MAX_NAME_BYTES)) { "An account name is 1 to $MAX_NAME_BYTES bytes." }
            account.label?.let { require(isPlain(it, 1, MAX_LABEL_BYTES)) { "A label is 1 to $MAX_LABEL_BYTES bytes." } }
            account.group?.let { require(isPlain(it, 1, MAX_LABEL_BYTES)) { "A group is 1 to $MAX_LABEL_BYTES bytes." } }
        }
        val distinct = payload.accounts.flatMap { listOfNotNull(it.label, it.group) }.distinct()
        require(distinct.size <= MAX_DICTIONARY) { "A pairing carries at most $MAX_DICTIONARY distinct labels and groups." }
        require(payload.addressBook.size <= MAX_ADDRESS_BOOK) { "A pairing carries at most $MAX_ADDRESS_BOOK Address Book entries." }
        for (entry in payload.addressBook) {
            require(isPlain(entry.id, 1, MAX_ID_BYTES) && isPlain(entry.address, 1, MAX_ADDRESS_BYTES)) {
                "An Address Book entry's id or address is out of range."
            }
            require(isPlain(entry.name, 1, MAX_ENTRY_NAME_BYTES)) { "An Address Book name is 1 to $MAX_ENTRY_NAME_BYTES bytes." }
            entry.label?.let { require(isPlain(it, 1, MAX_LABEL_BYTES)) { "An Address Book label is 1 to $MAX_LABEL_BYTES bytes." } }
        }
    }

    /** True when [text] is [minBytes] to [maxBytes] UTF-8 bytes with no control character. */
    private fun isPlain(text: String, minBytes: Int, maxBytes: Int): Boolean {
        val size = text.toByteArray(Charsets.UTF_8).size
        return size in minBytes..maxBytes && text.none { it.isISOControl() }
    }

    private fun uint(value: Long): Cbor = Cbor.Uint(value)

    private fun concat(parts: List<ByteArray>): ByteArray {
        val buffer = ByteBuffer.allocate(parts.sumOf { it.size })
        parts.forEach { buffer.put(it) }
        return buffer.array()
    }

    private fun refuse(): Nothing = throw IllegalArgumentException(NOT_A_PAIRING)

    /** The fields of [dict] whose keys lie in [keys], each at most once. Any other key refuses the code. */
    private fun fieldsOf(dict: Cbor.Dict, keys: LongRange): Map<Long, Cbor> {
        val fields = LinkedHashMap<Long, Cbor>()
        for ((key, value) in dict.entries) {
            val id = (key as? Cbor.Uint)?.value ?: refuse()
            if (id !in keys || fields.put(id, value) != null) refuse()
        }
        return fields
    }

    private fun required(fields: Map<Long, Cbor>, key: Long): Cbor = fields[key] ?: refuse()

    private fun uintValue(item: Cbor, max: Long): Long {
        val value = (item as? Cbor.Uint)?.value ?: refuse()
        if (value !in 0..max) refuse()
        return value
    }

    private fun items(item: Cbor, max: Int): List<Cbor> {
        val items = (item as? Cbor.Arr)?.items ?: refuse()
        if (items.size > max) refuse()
        return items
    }

    /** A byte string of at most [maxBytes] bytes. */
    private fun byteString(item: Cbor, maxBytes: Int): ByteArray {
        val value = (item as? Cbor.Bytes)?.value ?: refuse()
        if (value.size > maxBytes) refuse()
        return value
    }

    /** A text of [minBytes] to [maxBytes] UTF-8 bytes with no control character, which an empty text may be when [minBytes] is 0. */
    private fun textOf(item: Cbor, minBytes: Int, maxBytes: Int): String {
        val value = (item as? Cbor.Text)?.value ?: refuse()
        val size = value.toByteArray(Charsets.UTF_8).size
        if (size !in minBytes..maxBytes || value.any { it.isISOControl() }) refuse()
        return value
    }
}
