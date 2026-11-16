package app.lensly.app.export

import app.lensly.app.AddressBookEntry
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.parse.DecodeBudget
import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.framesOf
import app.lensly.core.wallet.AccountXpub
import com.sparrowwallet.hummingbird.UR

/**
 * One watch-only key of an App: a secp256k1 public key at [path], with the depth and parent fingerprint of its BIP32 node. Public data only.
 * [path] holds the derivation path as uint32 values, with the hardened bit (0x80000000) set on each hardened component.
 */
data class PairingKey(
    val path: List<Long>,
    val depth: Int,
    val parentFingerprint: Long,
    val publicKey: ByteArray,
    val chainCode: ByteArray,
) {
    companion object {
        private const val HARDENED = 0x80000000L

        /** The Ethereum account key m/44'/60'/0' of [xpub], which holds the addresses of every Ethereum account. */
        fun ethereumAccount(xpub: AccountXpub): PairingKey = PairingKey(
            path = listOf(HARDENED + 44, HARDENED + 60, HARDENED),
            depth = 3,
            parentFingerprint = xpub.parentFingerprint.toLong() and 0xFFFFFFFFL,
            publicKey = xpub.publicKey,
            chainCode = xpub.chainCode,
        )

        /** The watch-only key of a Bitcoin account: its path, depth, parent fingerprint and public key, as [account] holds them. */
        fun bitcoinAccount(account: BtcWatchOnlyAccount): PairingKey = PairingKey(
            path = account.path.path,
            depth = account.xpub.depth,
            parentFingerprint = account.xpub.parent,
            publicKey = account.xpub.publickeybytes.toByteArray(),
            chainCode = account.xpub.chaincode.toByteArray(),
        )
    }
}

/**
 * One account of an App. [key] is the index of the App's key the account derives from. [child] is the address index under that key, for an
 * App whose accounts are addresses under one key (Ethereum), or null when the key is the account itself (Bitcoin). [name], [label] and
 * [group] are what the owner gave the account in Lensly, or null when there is none.
 */
data class PairingAccount(
    val key: Int,
    val child: Int?,
    val name: String?,
    val label: String?,
    val group: String?,
)

/** One App of a wallet, as the pairing carries it: its route id, which is its name on the wire, with the keys and accounts it holds. */
data class PairingApp(val app: String, val keys: List<PairingKey>, val accounts: List<PairingAccount>)

/**
 * The pairing of one wallet: its master fingerprint, its name, the keys and accounts of every App it holds, and the Address Book that
 * Lensly sends with it. [addressBook] is empty when the owner saved no entries, and a code without entries carries no book.
 */
data class PairingPayload(
    val fingerprint: Long,
    val name: String,
    val apps: List<PairingApp>,
    val addressBook: List<AddressBookEntry> = emptyList(),
)

/**
 * The AirVault pairing: the code Lensly shows for a wallet, one for all of its Apps, so the online companion imports every account and its
 * names at once. The code is a `ur:airvault-pairing`, animated when it is long. Its CBOR map is versioned: a reader refuses a version it does
 * not know, so it never reads a newer layout as its own. The payload holds public data only: keys, chain codes, paths and names.
 *
 * The layout, version 1, with integer keys:
 *  - payload: { 1: version, 2: fingerprint (uint32), 3: name, 4: [app], 5: [address] (optional: absent when the Address Book is empty) }
 *  - app: { 1: app id, 2: [key], 3: [account] }
 *  - key: { 1: [path component (uint32)], 2: depth, 3: parent fingerprint (uint32), 4: public key (33 bytes), 5: chain code (32 bytes) }
 *  - account: { 1: key index, 2: child index (optional), 3: name (optional), 4: label (optional), 5: group (optional) }
 *  - address: { 1: id, 2: address, 3: name, 4: label (optional) }, one Address Book entry as Lensly saved it
 *
 * Key 5 of the payload was added to version 1 without a new version. A reader of an earlier build reads only keys 1 to 4, so it refuses a
 * code that carries an Address Book, as it refuses any key it does not know. A code with an empty book is the same code as before.
 */
object PairingCodec {
    /** The UR type of the pairing. */
    const val UR_TYPE = "airvault-pairing"

    /** The layout version this build writes and reads. */
    const val VERSION = 1L

    /** The most Apps one pairing carries, and the most keys and accounts one App carries. */
    const val MAX_APPS = 8
    const val MAX_KEYS = 512
    const val MAX_ACCOUNTS = 512

    /**
     * The longest wallet or account name, and the longest label or group, in UTF-8 bytes. A reader stores each within the slot of a
     * record, so a name that is longer is refused, and a label or group of 16 characters at four bytes each still fits.
     */
    const val MAX_NAME_BYTES = 96
    const val MAX_LABEL_BYTES = 64

    /** The most Address Book entries one pairing carries. Lensly sends the first of them, and the owner is told when it leaves the rest out. */
    const val MAX_ADDRESS_BOOK = 500

    /** The longest entry id and address, and the longest entry name, in UTF-8 bytes: a UUID, an address, and 32 characters at four bytes. */
    const val MAX_ID_BYTES = 64
    const val MAX_ADDRESS_BYTES = 64
    const val MAX_ENTRY_NAME_BYTES = 128

    private const val NOT_A_PAIRING = "This is not a pairing code from Lensly."
    private const val UINT32_MAX = 0xFFFFFFFFL
    private const val MAX_CHILD = 0x7FFFFFFFL
    private const val MAX_PATH = 16
    private const val PUBLIC_KEY_BYTES = 33
    private const val CHAIN_CODE_BYTES = 32
    private val APP_ID = Regex("[a-z0-9-]{1,32}")

    /** The frames of the pairing [payload]: one code when it fits, otherwise the uncoded parts of an animated code. */
    fun urParts(payload: PairingPayload): List<String> =
        partsOf(framesOf(UR(UR_TYPE, encode(payload)), DEFAULT_MAX_FRAGMENT))

    /**
     * The pairing that [ur] carries.
     * @throws IllegalArgumentException with a message the owner can read, when [ur] is not a pairing, holds an unknown version, or breaks the layout.
     */
    fun fromUr(ur: UR): PairingPayload {
        require(ur.type.equals(UR_TYPE, ignoreCase = true)) { NOT_A_PAIRING }
        return decode(ur.cborBytes)
    }

    /** The CBOR bytes of [payload], which must be valid. */
    fun encode(payload: PairingPayload): ByteArray {
        validate(payload)
        val fields = mutableListOf(
            uint(1) to uint(VERSION),
            uint(2) to uint(payload.fingerprint),
            uint(3) to Cbor.Text(payload.name),
            uint(4) to Cbor.Arr(payload.apps.map { app -> encodeApp(app) }),
        )
        // An empty book is left out, so a pairing without entries is the code an earlier build wrote.
        if (payload.addressBook.isNotEmpty()) fields += uint(5) to Cbor.Arr(payload.addressBook.map { encodeAddress(it) })
        return Cbor.encode(Cbor.Dict(fields))
    }

    /**
     * The pairing that [bytes] encode. The version is read first, so a newer layout is refused by its version and not by its fields.
     * @throws IllegalArgumentException with a message the owner can read.
     */
    fun decode(bytes: ByteArray): PairingPayload {
        val root = try {
            Cbor.decode(bytes, DecodeBudget("CBOR", ParserLimits.MAX_PAIRING_ITEMS))
        } catch (e: Exception) {
            throw IllegalArgumentException(NOT_A_PAIRING, e)
        }
        // The version is read before any other field, so a newer layout with fields this build does not know is refused by its version.
        val version = (root as? Cbor.Dict)?.entries
            ?.firstOrNull { (key, _) -> (key as? Cbor.Uint)?.value == 1L }
            ?.let { (_, value) -> (value as? Cbor.Uint)?.value }
            ?: refuse()
        if (version > VERSION) {
            throw IllegalArgumentException(
                "This pairing uses format $version, and this app reads format $VERSION. Update the app, then pair again.",
            )
        }
        if (version != VERSION) refuse()
        return try {
            val fields = fieldsOf(root as Cbor.Dict, 1L..5L)
            PairingPayload(
                fingerprint = uintValue(required(fields, 2L), UINT32_MAX),
                name = text(required(fields, 3L), MAX_NAME_BYTES),
                apps = items(required(fields, 4L), MAX_APPS).map { decodeApp(it) },
                addressBook = fields[5L]?.let { book -> items(book, MAX_ADDRESS_BOOK).map { decodeAddress(it) } } ?: emptyList(),
            ).also { validate(it) }
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(NOT_A_PAIRING, e)
        }
    }

    private fun encodeApp(app: PairingApp): Cbor = Cbor.Dict(
        listOf(
            uint(1) to Cbor.Text(app.app),
            uint(2) to Cbor.Arr(app.keys.map { encodeKey(it) }),
            uint(3) to Cbor.Arr(app.accounts.map { encodeAccount(it) }),
        ),
    )

    private fun encodeKey(key: PairingKey): Cbor = Cbor.Dict(
        listOf(
            uint(1) to Cbor.Arr(key.path.map { uint(it) }),
            uint(2) to uint(key.depth.toLong()),
            uint(3) to uint(key.parentFingerprint),
            uint(4) to Cbor.Bytes(key.publicKey),
            uint(5) to Cbor.Bytes(key.chainCode),
        ),
    )

    private fun encodeAccount(account: PairingAccount): Cbor {
        val entries = mutableListOf<Pair<Cbor, Cbor>>(uint(1) to uint(account.key.toLong()))
        account.child?.let { entries += uint(2) to uint(it.toLong()) }
        account.name?.let { entries += uint(3) to Cbor.Text(it) }
        account.label?.let { entries += uint(4) to Cbor.Text(it) }
        account.group?.let { entries += uint(5) to Cbor.Text(it) }
        return Cbor.Dict(entries)
    }

    private fun decodeApp(item: Cbor): PairingApp {
        val fields = fieldsOf(item as? Cbor.Dict ?: refuse(), 1L..3L)
        return PairingApp(
            app = required(fields, 1L).let { (it as? Cbor.Text)?.value ?: refuse() },
            keys = items(required(fields, 2L), MAX_KEYS).map { decodeKey(it) },
            accounts = items(required(fields, 3L), MAX_ACCOUNTS).map { decodeAccount(it) },
        )
    }

    private fun decodeKey(item: Cbor): PairingKey {
        val fields = fieldsOf(item as? Cbor.Dict ?: refuse(), 1L..5L)
        return PairingKey(
            path = items(required(fields, 1L), MAX_PATH).map { uintValue(it, UINT32_MAX) },
            depth = uintValue(required(fields, 2L), 0xFFL).toInt(),
            parentFingerprint = uintValue(required(fields, 3L), UINT32_MAX),
            publicKey = bytes(required(fields, 4L), PUBLIC_KEY_BYTES),
            chainCode = bytes(required(fields, 5L), CHAIN_CODE_BYTES),
        )
    }

    private fun decodeAccount(item: Cbor): PairingAccount {
        val fields = fieldsOf(item as? Cbor.Dict ?: refuse(), 1L..5L)
        return PairingAccount(
            key = uintValue(required(fields, 1L), Int.MAX_VALUE.toLong()).toInt(),
            child = fields[2L]?.let { uintValue(it, MAX_CHILD).toInt() },
            name = fields[3L]?.let { text(it, MAX_NAME_BYTES) },
            label = fields[4L]?.let { text(it, MAX_LABEL_BYTES) },
            group = fields[5L]?.let { text(it, MAX_LABEL_BYTES) },
        )
    }

    private fun encodeAddress(entry: AddressBookEntry): Cbor {
        val entries = mutableListOf<Pair<Cbor, Cbor>>(
            uint(1) to Cbor.Text(entry.id),
            uint(2) to Cbor.Text(entry.address),
            uint(3) to Cbor.Text(entry.name),
        )
        entry.label?.let { entries += uint(4) to Cbor.Text(it) }
        return Cbor.Dict(entries)
    }

    private fun decodeAddress(item: Cbor): AddressBookEntry {
        val fields = fieldsOf(item as? Cbor.Dict ?: refuse(), 1L..4L)
        return AddressBookEntry(
            id = text(required(fields, 1L), MAX_ID_BYTES),
            address = text(required(fields, 2L), MAX_ADDRESS_BYTES),
            name = text(required(fields, 3L), MAX_ENTRY_NAME_BYTES),
            label = fields[4L]?.let { text(it, MAX_LABEL_BYTES) },
        )
    }

    /** Checks the invariants of [payload], so an encoder never writes a code a reader would refuse. */
    private fun validate(payload: PairingPayload) {
        require(payload.fingerprint in 0..UINT32_MAX) { "The fingerprint is a uint32." }
        require(isPlainText(payload.name, MAX_NAME_BYTES)) { "A name is 1 to $MAX_NAME_BYTES bytes of plain text." }
        require(payload.apps.size <= MAX_APPS) { "A pairing carries at most $MAX_APPS Apps." }
        require(payload.apps.map { it.app }.distinct().size == payload.apps.size) { "An App is listed once." }
        for (app in payload.apps) {
            require(APP_ID.matches(app.app)) { "An App id is 1 to 32 characters of a-z, 0-9 and hyphen." }
            require(app.keys.size <= MAX_KEYS && app.accounts.size <= MAX_ACCOUNTS) { "An App holds too many keys or accounts." }
            for (key in app.keys) {
                require(key.path.size in 1..MAX_PATH && key.path.all { it in 0..UINT32_MAX }) { "A key path is 1 to $MAX_PATH uint32 values." }
                require(key.depth in 0..0xFF && key.parentFingerprint in 0..UINT32_MAX) { "A key's depth or parent fingerprint is out of range." }
                require(key.publicKey.size == PUBLIC_KEY_BYTES && key.publicKey[0].toInt() in listOf(2, 3)) {
                    "A key's public key is 33 bytes, compressed."
                }
                require(key.chainCode.size == CHAIN_CODE_BYTES) { "A key's chain code is 32 bytes." }
            }
            for (account in app.accounts) {
                require(account.key in app.keys.indices) { "An account names a key the App does not hold." }
                require(account.child == null || account.child.toLong() in 0..MAX_CHILD) { "A child index is non-hardened." }
                account.name?.let { require(isPlainText(it, MAX_NAME_BYTES)) { "An account name is 1 to $MAX_NAME_BYTES bytes." } }
                account.label?.let { require(isPlainText(it, MAX_LABEL_BYTES)) { "A label is 1 to $MAX_LABEL_BYTES bytes." } }
                account.group?.let { require(isPlainText(it, MAX_LABEL_BYTES)) { "A group is 1 to $MAX_LABEL_BYTES bytes." } }
            }
        }
        require(payload.addressBook.size <= MAX_ADDRESS_BOOK) { "A pairing carries at most $MAX_ADDRESS_BOOK Address Book entries." }
        for (entry in payload.addressBook) {
            require(isPlainText(entry.id, MAX_ID_BYTES) && isPlainText(entry.address, MAX_ADDRESS_BYTES)) {
                "An Address Book entry's id or address is out of range."
            }
            require(isPlainText(entry.name, MAX_ENTRY_NAME_BYTES)) { "An Address Book name is 1 to $MAX_ENTRY_NAME_BYTES bytes." }
            entry.label?.let { require(isPlainText(it, MAX_LABEL_BYTES)) { "An Address Book label is 1 to $MAX_LABEL_BYTES bytes." } }
        }
    }

    /** True when [text] is 1 to [maxBytes] UTF-8 bytes with no control character. */
    private fun isPlainText(text: String, maxBytes: Int): Boolean {
        val size = text.toByteArray(Charsets.UTF_8).size
        return size in 1..maxBytes && text.none { it.isISOControl() }
    }

    private fun uint(value: Long): Cbor = Cbor.Uint(value)

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

    private fun bytes(item: Cbor, size: Int): ByteArray {
        val value = (item as? Cbor.Bytes)?.value ?: refuse()
        if (value.size != size) refuse()
        return value
    }

    private fun text(item: Cbor, maxBytes: Int): String {
        val value = (item as? Cbor.Text)?.value ?: refuse()
        if (!isPlainText(value, maxBytes)) refuse()
        return value
    }
}
