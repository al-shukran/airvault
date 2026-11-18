package app.lensly.app.export

import app.lensly.app.AddressBookEntry
import app.lensly.app.autoAccountName
import app.lensly.app.evmAddressOf
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.parse.DecodeBudget
import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.framesOf
import com.sparrowwallet.hummingbird.UR
import java.nio.ByteBuffer
import java.util.UUID

/**
 * One account's key, as the pairing carries it. [path] is the BIP32 path of the key: m/purpose'/coin'/account'/suffix, where the account
 * index is the third component and the suffix (if any) is not hardened. [material] is what the App derives the account's address from: the
 * 20-byte address of an Ethereum account, the 33-byte compressed public key of a Bitcoin account, or the 32-byte Ed25519 public key of a
 * Solana or Algorand account. [chainCode] is the 32-byte chain code of a Bitcoin key, which derives its receive and change addresses, and
 * null for the other Apps. Public data only.
 */
data class PairingKey(
    val path: List<Long>,
    val material: ByteArray,
    val chainCode: ByteArray? = null,
) {
    companion object {
        private const val HARDENED = 0x80000000L

        /**
         * The key of Ethereum account [account], m/44'/60'/[account]'/0/0. [address] is the account's address, which is what the pairing carries
         * of it: Lumio shows the address and sends it in a request, and no Lumio path derives a child or signs with the key.
         * @throws IllegalArgumentException when [address] is not an Ethereum address.
         */
        fun ethereumAddress(account: Int, address: String): PairingKey {
            val canonical = requireNotNull(evmAddressOf(address)) { "An Ethereum account's address is 0x and 40 hex digits." }
            return PairingKey(
                path = listOf(HARDENED + 44, HARDENED + 60, HARDENED + account, 0, 0),
                material = ByteArray(20) { i -> canonical.substring(2 + 2 * i, 4 + 2 * i).toInt(16).toByte() },
            )
        }

        /** The key of Solana account [account], m/44'/501'/[account]'. [publicKey] is the 32-byte Ed25519 key, which is the address. */
        fun solanaAccount(account: Int, publicKey: ByteArray): PairingKey {
            require(publicKey.size == 32) { "An Ed25519 public key is 32 bytes." }
            return PairingKey(path = listOf(HARDENED + 44, HARDENED + 501, HARDENED + account), material = publicKey)
        }

        /** The key of Algorand account [account], m/44'/283'/[account]'/0/0. [publicKey] is the 32-byte Ed25519 key, which is the address. */
        fun algorandAccount(account: Int, publicKey: ByteArray): PairingKey {
            require(publicKey.size == 32) { "An Ed25519 public key is 32 bytes." }
            return PairingKey(path = listOf(HARDENED + 44, HARDENED + 283, HARDENED + account, 0, 0), material = publicKey)
        }

        /** The key of a Bitcoin account: its path, its compressed public key and its chain code. */
        fun bitcoinAccount(account: BtcWatchOnlyAccount): PairingKey = PairingKey(
            path = account.path.path,
            material = account.xpub.publickeybytes.toByteArray(),
            chainCode = account.xpub.chaincode.toByteArray(),
        )
    }
}

/**
 * One account of an App. [key] is the position of the account's key in its App's keys, so the account at position N describes the key at
 * position N. [name] is the Display Name, or the Auto-Name when the owner gave the account none. [label] and [group] are null when there is none.
 */
data class PairingAccount(
    val key: Int,
    val name: String,
    val label: String?,
    val group: String?,
)

/** One App of a wallet, as the pairing carries it: its route id, which is its name on the wire, with the keys and accounts it holds. */
data class PairingApp(val app: String, val keys: List<PairingKey>, val accounts: List<PairingAccount>)

/**
 * The pairing of one wallet: its master fingerprint, its name, the base of the Auto-Name of its accounts, the keys and accounts of every App
 * it holds, and the Address Book that Lensly sends with it. [autoNameBase] is the base of the Auto-Name that [autoAccountName] builds for an
 * account, so an account named by its Auto-Name is sent with no name, and the reader builds the name from the base and the index. [addressBook]
 * is empty when the owner saved no entries, and a code without entries carries no book.
 */
data class PairingPayload(
    val fingerprint: Long,
    val name: String,
    val autoNameBase: String,
    val apps: List<PairingApp>,
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
 * The AirVault pairing: the code Lensly shows for a wallet, one for all of its Apps, so the online companion imports every account and its
 * names at once. The code is a `ur:x-airvault-pairing`, animated when it is long. Its CBOR is versioned: a reader refuses a version it does
 * not know, so it never reads a newer layout as its own, and it does not read an older one. The payload holds public data only: keys, chain
 * codes, paths, addresses and names.
 *
 * The layout, with integer keys. The code's CBOR is an envelope:
 *  - envelope: { 1: version (6), 6: encoding, 7: body (bytes) }. Encoding 0 keeps the body as it is, and encoding 2 holds it Brotli-compressed.
 *  - body, once it is decompressed: { 2: fingerprint (uint32), 3: name, 4: auto-name base (may be empty), 5: [app],
 *    6: [label or group] (optional: absent when no account has a label or group), 7: [address] (optional: absent when the Address Book is empty) }
 *  - app: { 1: app id, 2: [run] }
 *  - run: a block of consecutive accounts of one App, whose keys have one path shape and one key length, and whose indices are consecutive:
 *    { 1: purpose, 2: coin type, 3: first account index, 4: count, 5: [suffix] (the path after the index), 6: keys (bytes, count × key length),
 *    7: chain codes (bytes, count × 32, optional), 8: [name] (optional: absent when every account is named by its Auto-Name),
 *    9: [label index] (optional), 10: [group index] (optional) }. The key of account i of a run is at m/purpose'/coin'/i'/suffix. A name of ""
 *    is the Auto-Name: the auto-name base, a space and the account index. A label or group index of 0 is none, and n is entry n-1 of key 6.
 *  - key length: 20 bytes (an Ethereum address), 33 bytes (a compressed secp256k1 key), or 32 bytes (an Ed25519 key, with no prefix).
 *  - address: { 1: id, 2: address, 3: name, 4: label (optional) }, one Address Book entry. An id that is a lower-case UUID is 16 bytes, and an
 *    address that is an EVM address in checksum form is 20 bytes. Any other id or address is text.
 *
 * The body is at most [MAX_BODY_BYTES] bytes, compressed or not. The encoder keeps it compressed only when that makes it smaller.
 */
object PairingCodec {
    /** The UR type of the pairing. The name before 2.9 was `airvault-pairing`, and its codes are refused: the owner pairs again. */
    const val UR_TYPE = "x-airvault-pairing"

    /**
     * The layout version of this build, which it writes and reads. A code of any other version is refused with a message that says why. There is
     * no reader for an older version: an older code must be paired again.
     */
    const val VERSION = 6L

    /**
     * The largest CBOR fragment of one animated frame, in bytes. It is the only frame size of the pairing, so a change of it is one edit here.
     * A larger fragment means fewer frames, and the scanner reads the fragments of any size.
     */
    const val FRAGMENT_BYTES = 300

    /** The largest body in bytes, compressed or not. A reader refuses a larger body, so a body it decompresses is bounded by this. */
    const val MAX_BODY_BYTES = 512 * 1024

    /** The most Apps one pairing carries, and the most keys (and accounts) one App carries. */
    const val MAX_APPS = 8
    const val MAX_KEYS = 512

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
    /** The refusal of a code of an older layout, which Lumio also shows for a code of the old UR type: the owner pairs again. */
    const val OLDER_CODE = "This pairing code is from an older Lensly. Update both apps and pair again."
    private const val UINT32_MAX = 0xFFFFFFFFL
    private const val HARDENED = 0x80000000L
    private const val INDEX_MASK = 0x7FFFFFFFL
    private const val CHAIN_CODE_BYTES = 32
    private const val UUID_BYTES = 16
    private const val EVM_ADDRESS_BYTES = 20
    private val KEY_BYTES = setOf(20, 32, 33)
    private const val ENCODING_RAW = 0L
    private const val ENCODING_BROTLI = 2L
    private val APP_ID = Regex("[a-z0-9-]{1,32}")

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
        require(ur.type.equals(UR_TYPE, ignoreCase = true)) { NOT_A_PAIRING }
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
        if (version < VERSION) throw IllegalArgumentException(OLDER_CODE)
        if (version > VERSION) {
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
        val autoNameBase = textOf(required(fields, 4L), 0, MAX_NAME_BYTES)
        val dictionary = fields[6L]?.let { items(it, MAX_DICTIONARY).map { item -> textOf(item, 1, MAX_LABEL_BYTES) } } ?: emptyList()
        val payload = PairingPayload(
            fingerprint = uintValue(required(fields, 2L), UINT32_MAX),
            name = textOf(required(fields, 3L), 1, MAX_NAME_BYTES),
            autoNameBase = autoNameBase,
            apps = items(required(fields, 5L), MAX_APPS).map { decodeApp(it, autoNameBase, dictionary) },
            addressBook = fields[7L]?.let { book -> items(book, MAX_ADDRESS_BOOK).map { decodeAddress(it) } } ?: emptyList(),
        )
        validate(payload)
        return payload
    }

    /** The fields of a body, in key order. A body carries no version of its own: the envelope does. */
    private fun bodyFields(payload: PairingPayload): List<Pair<Cbor, Cbor>> {
        val dictionary = payload.apps.flatMap { app -> app.accounts.flatMap { listOfNotNull(it.label, it.group) } }.distinct()
        val indexes = dictionary.withIndex().associate { (index, text) -> text to index + 1 }
        val fields = mutableListOf(
            uint(2) to uint(payload.fingerprint),
            uint(3) to Cbor.Text(payload.name),
            uint(4) to Cbor.Text(payload.autoNameBase),
            uint(5) to Cbor.Arr(payload.apps.map { app -> encodeApp(app, payload.autoNameBase, indexes) }),
        )
        // Each of these is left out when it is empty, so a code that has none of them carries no key 6 or 7.
        if (dictionary.isNotEmpty()) fields += uint(6) to Cbor.Arr(dictionary.map { Cbor.Text(it) })
        if (payload.addressBook.isNotEmpty()) fields += uint(7) to Cbor.Arr(payload.addressBook.map { encodeAddress(it) })
        return fields
    }

    /** A block of accounts of one App that [encodeApp] writes as one run. */
    private class Run(
        val purpose: Long,
        val coin: Long,
        val first: Long,
        val suffix: List<Long>,
        val keys: MutableList<PairingKey> = mutableListOf(),
        val accounts: MutableList<PairingAccount> = mutableListOf(),
    )

    private fun encodeApp(app: PairingApp, autoNameBase: String, indexes: Map<String, Int>): Cbor {
        val runs = mutableListOf<Run>()
        app.keys.forEachIndexed { position, key ->
            val purpose = key.path[0] and INDEX_MASK
            val coin = key.path[1] and INDEX_MASK
            val index = key.path[2] and INDEX_MASK
            val suffix = key.path.drop(3)
            val last = runs.lastOrNull()
            if (last != null && last.purpose == purpose && last.coin == coin && last.suffix == suffix &&
                index == last.first + last.keys.size &&
                last.keys[0].material.size == key.material.size &&
                (last.keys[0].chainCode == null) == (key.chainCode == null)
            ) {
                last.keys += key
                last.accounts += app.accounts[position]
            } else {
                runs += Run(purpose, coin, index, suffix).also {
                    it.keys += key
                    it.accounts += app.accounts[position]
                }
            }
        }
        return Cbor.Dict(
            listOf(uint(1) to Cbor.Text(app.app), uint(2) to Cbor.Arr(runs.map { encodeRun(it, autoNameBase, indexes) })),
        )
    }

    private fun encodeRun(run: Run, autoNameBase: String, indexes: Map<String, Int>): Cbor {
        val fields = mutableListOf(
            uint(1) to uint(run.purpose),
            uint(2) to uint(run.coin),
            uint(3) to uint(run.first),
            uint(4) to uint(run.keys.size.toLong()),
            uint(5) to Cbor.Arr(run.suffix.map { uint(it) }),
            uint(6) to Cbor.Bytes(concat(run.keys.map { it.material })),
        )
        if (run.keys[0].chainCode != null) fields += uint(7) to Cbor.Bytes(concat(run.keys.map { it.chainCode!! }))
        val names = run.accounts.mapIndexed { position, account ->
            if (account.name == autoAccountName(autoNameBase, (run.first + position).toInt())) "" else account.name
        }
        // The names are left out when every account is named by its Auto-Name, and the labels and groups when none has one.
        if (names.any { it.isNotEmpty() }) fields += uint(8) to Cbor.Arr(names.map { Cbor.Text(it) })
        if (run.accounts.any { it.label != null }) fields += uint(9) to Cbor.Arr(run.accounts.map { uint(indexOf(it.label, indexes)) })
        if (run.accounts.any { it.group != null }) fields += uint(10) to Cbor.Arr(run.accounts.map { uint(indexOf(it.group, indexes)) })
        return Cbor.Dict(fields)
    }

    private fun decodeApp(item: Cbor, autoNameBase: String, dictionary: List<String>): PairingApp {
        val fields = fieldsOf(item as? Cbor.Dict ?: refuse(), 1L..2L)
        val keys = mutableListOf<PairingKey>()
        val accounts = mutableListOf<PairingAccount>()
        for (run in items(required(fields, 2L), MAX_KEYS)) {
            decodeRun(run, autoNameBase, dictionary, keys, accounts)
        }
        return PairingApp(
            app = textOf(required(fields, 1L), 1, 32),
            keys = keys,
            accounts = accounts,
        )
    }

    /** Appends the keys and accounts of one run to [keys] and [accounts], with each account's index counted from the run's first index. */
    private fun decodeRun(
        item: Cbor,
        autoNameBase: String,
        dictionary: List<String>,
        keys: MutableList<PairingKey>,
        accounts: MutableList<PairingAccount>,
    ) {
        val fields = fieldsOf(item as? Cbor.Dict ?: refuse(), 1L..10L)
        val purpose = uintValue(required(fields, 1L), INDEX_MASK)
        val coin = uintValue(required(fields, 2L), INDEX_MASK)
        val first = uintValue(required(fields, 3L), INDEX_MASK)
        val count = uintValue(required(fields, 4L), MAX_KEYS.toLong()).toInt()
        if (count < 1 || first + count - 1 > INDEX_MASK) refuse()
        val suffix = items(required(fields, 5L), MAX_SUFFIX).map { uintValue(it, INDEX_MASK) }
        val material = (required(fields, 6L) as? Cbor.Bytes)?.value ?: refuse()
        val length = material.size / count
        if (material.size % count != 0 || length !in KEY_BYTES) refuse()
        val chainCodes = fields[7L]?.let { value ->
            val bytes = (value as? Cbor.Bytes)?.value ?: refuse()
            if (bytes.size != count * CHAIN_CODE_BYTES) refuse()
            bytes
        }
        val names = fields[8L]?.let { value -> column(value, count).map { textOf(it, 0, MAX_NAME_BYTES) } }
        val labels = fields[9L]?.let { value -> column(value, count).map { labelOf(it, dictionary) } }
        val groups = fields[10L]?.let { value -> column(value, count).map { labelOf(it, dictionary) } }
        for (position in 0 until count) {
            val index = (first + position).toInt()
            // An empty name is the Auto-Name, and so is every name of a run that has no names column.
            val explicit = names?.get(position).orEmpty()
            val name = if (explicit.isEmpty()) autoAccountName(autoNameBase, index) else explicit
            keys += PairingKey(
                path = listOf(HARDENED or purpose, HARDENED or coin, HARDENED or index.toLong()) + suffix,
                material = material.copyOfRange(position * length, (position + 1) * length),
                chainCode = chainCodes?.copyOfRange(position * CHAIN_CODE_BYTES, (position + 1) * CHAIN_CODE_BYTES),
            )
            accounts += PairingAccount(
                key = keys.size - 1,
                name = name,
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
        require(payload.apps.size <= MAX_APPS) { "A pairing carries at most $MAX_APPS Apps." }
        require(payload.apps.map { it.app }.distinct().size == payload.apps.size) { "An App is listed once." }
        for (app in payload.apps) {
            require(APP_ID.matches(app.app)) { "An App id is 1 to 32 characters of a-z, 0-9 and hyphen." }
            require(app.keys.size <= MAX_KEYS && app.accounts.size == app.keys.size) { "An App holds too many keys, or its accounts and keys differ." }
            for ((position, key) in app.keys.withIndex()) {
                require(key.path.size in 3..3 + MAX_SUFFIX && key.path.take(3).all { it in HARDENED..(HARDENED + INDEX_MASK) }) {
                    "A key path is m/purpose'/coin'/account' with up to $MAX_SUFFIX components after it."
                }
                require(key.path.drop(3).all { it in 0..INDEX_MASK }) { "A key path's suffix is not hardened." }
                require(key.material.size in KEY_BYTES) { "A key is 20 bytes (an Ethereum address), 32 bytes (Ed25519) or 33 bytes (compressed)." }
                require(key.chainCode == null || key.chainCode.size == CHAIN_CODE_BYTES) { "A key's chain code is 32 bytes." }
                val account = app.accounts[position]
                require(account.key == position) { "An account names the key at its own position." }
                require(isPlain(account.name, 1, MAX_NAME_BYTES)) { "An account name is 1 to $MAX_NAME_BYTES bytes." }
                account.label?.let { require(isPlain(it, 1, MAX_LABEL_BYTES)) { "A label is 1 to $MAX_LABEL_BYTES bytes." } }
                account.group?.let { require(isPlain(it, 1, MAX_LABEL_BYTES)) { "A group is 1 to $MAX_LABEL_BYTES bytes." } }
            }
        }
        val distinct = payload.apps.flatMap { app -> app.accounts.flatMap { listOfNotNull(it.label, it.group) } }.distinct()
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
