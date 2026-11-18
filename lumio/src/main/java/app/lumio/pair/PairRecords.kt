package app.lumio.pair

import app.lensly.app.evmAddressOf
import app.lensly.app.export.PairingCodec
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.btc.ScriptType
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.byteVector
import fr.acinq.bitcoin.byteVector32
import java.nio.ByteBuffer

/**
 * The bytes of one paired wallet's records, as Lumio writes them into a wallet folder. Each record starts with a version byte. The META
 * and account records hold length-prefixed text and fixed fields in big-endian order, and the Bitcoin record holds the watch-only Accounts.
 * A record holds public data only. Each record type has one layout, and every decoder rejects a record it cannot read in full,
 * including one of another version. The rejection is an error for the caller, never a missing pairing.
 */
internal object PairRecords {
    /** The layout of the META and account records. */
    private const val VERSION: Byte = 4

    /** The layout of the Bitcoin record. */
    private const val BITCOIN_VERSION: Byte = 2

    /** The layout of the Solana record: the count, then the 32-byte public key of each account. */
    private const val SOLANA_VERSION: Byte = 1
    private const val SOLANA_KEY_BYTES = 32

    /** The layout of the Algorand record: the count, then the 32-byte public key of each account. */
    private const val ALGORAND_VERSION: Byte = 1
    private const val ALGORAND_KEY_BYTES = 32
    private const val COMPRESSED_KEY_BYTES = 33
    private const val CHAIN_CODE_BYTES = 32
    private const val FINGERPRINT_BYTES = 4
    private const val UINT32_MASK = 0xFFFFFFFFL
    private const val UNSIGNED_BYTE_MASK = 0xFF
    private const val ACCOUNT_PATH_LENGTH = 3
    private const val BITCOIN_ACCOUNT_BYTES = 3 + ACCOUNT_PATH_LENGTH * FINGERPRINT_BYTES + COMPRESSED_KEY_BYTES + CHAIN_CODE_BYTES

    /** An Ethereum address in its EIP-55 form, which is what the META record holds for each account: "0x" and 40 hex digits. */
    private const val ADDRESS_CHARS = 42

    /** The META record's fixed part before the name: the version, the fingerprint, the creation time and the name's length. */
    private const val META_HEADER_BYTES = 1 + FINGERPRINT_BYTES + 8 + 1

    /** The META record's account count, which follows the name. */
    private const val META_COUNT_BYTES = 1

    /**
     * The META record: the wallet's master fingerprint, when Lumio first paired it, its creation time, its name, and the address of each of its
     * Ethereum accounts. The number of accounts is the number of addresses. Each address is in its EIP-55 form.
     */
    class Meta(val fingerprint: Long, val createdAtMs: Long, val name: String, val addresses: List<String>)

    fun encodeMeta(meta: Meta): ByteArray {
        val name = meta.name.toByteArray(Charsets.UTF_8)
        require(name.size in 1..PairingCodec.MAX_NAME_BYTES) { "A wallet name holds 1 to ${PairingCodec.MAX_NAME_BYTES} bytes." }
        require(meta.addresses.size in 1..PairImport.MAX_ETHEREUM_ACCOUNTS) { "A wallet holds 1 to ${PairImport.MAX_ETHEREUM_ACCOUNTS} accounts." }
        require(meta.addresses.all { evmAddressOf(it) == it }) { "An account's address is an Ethereum address in EIP-55 form." }
        val buffer = ByteBuffer.allocate(META_HEADER_BYTES + name.size + META_COUNT_BYTES + meta.addresses.size * ADDRESS_CHARS)
            .put(VERSION)
            .putInt(meta.fingerprint.toInt())
            .putLong(meta.createdAtMs)
            .put(name.size.toByte())
            .put(name)
            .put(meta.addresses.size.toByte())
        meta.addresses.forEach { buffer.put(it.toByteArray(Charsets.US_ASCII)) }
        return buffer.array()
    }

    fun decodeMeta(bytes: ByteArray): Meta {
        require(bytes.size > META_HEADER_BYTES && bytes[0] == VERSION) { "Not a pairing record." }
        val buffer = ByteBuffer.wrap(bytes)
        buffer.get()
        val fingerprint = buffer.int.toLong() and UINT32_MASK
        val createdAtMs = buffer.long
        val length = buffer.get().toInt() and UNSIGNED_BYTE_MASK
        require(length in 1..PairingCodec.MAX_NAME_BYTES && buffer.remaining() > length + META_COUNT_BYTES) { "Not a pairing record." }
        val name = String(bytes, buffer.position(), length, Charsets.UTF_8)
        buffer.position(buffer.position() + length)
        val accountCount = buffer.get().toInt() and UNSIGNED_BYTE_MASK
        require(accountCount in 1..PairImport.MAX_ETHEREUM_ACCOUNTS && buffer.remaining() == accountCount * ADDRESS_CHARS) {
            "Not a pairing record."
        }
        val addresses = List(accountCount) { index ->
            String(bytes, buffer.position() + index * ADDRESS_CHARS, ADDRESS_CHARS, Charsets.US_ASCII)
        }
        require(addresses.all { evmAddressOf(it) == it }) { "Not a pairing record." }
        return Meta(fingerprint, createdAtMs, name, addresses)
    }

    /** One Ethereum account's record: the name, label and group that Lensly gave it. An absent field is stored with length 0. */
    class Account(val name: String?, val label: String?, val group: String?)

    fun encodeAccount(account: Account): ByteArray {
        val fields = listOf(account.name, account.label, account.group).map { it.orEmpty().toByteArray(Charsets.UTF_8) }
        require(fields[0].size <= PairingCodec.MAX_NAME_BYTES) { "A name holds at most ${PairingCodec.MAX_NAME_BYTES} bytes." }
        require(fields[1].size <= PairingCodec.MAX_LABEL_BYTES && fields[2].size <= PairingCodec.MAX_LABEL_BYTES) {
            "A label or group holds at most ${PairingCodec.MAX_LABEL_BYTES} bytes."
        }
        val buffer = ByteBuffer.allocate(1 + fields.sumOf { 1 + it.size }).put(VERSION)
        fields.forEach { buffer.put(it.size.toByte()).put(it) }
        return buffer.array()
    }

    fun decodeAccount(bytes: ByteArray): Account {
        require(bytes.isNotEmpty() && bytes[0] == VERSION) { "Not an account record." }
        var position = 1
        fun field(maxBytes: Int): String? {
            require(position < bytes.size) { "Not an account record." }
            val length = bytes[position].toInt() and UNSIGNED_BYTE_MASK
            require(length <= maxBytes && position + 1 + length <= bytes.size) { "Not an account record." }
            val text = if (length == 0) null else String(bytes, position + 1, length, Charsets.UTF_8)
            position += 1 + length
            return text
        }
        val account = Account(
            name = field(PairingCodec.MAX_NAME_BYTES),
            label = field(PairingCodec.MAX_LABEL_BYTES),
            group = field(PairingCodec.MAX_LABEL_BYTES),
        )
        require(position == bytes.size) { "Not an account record." }
        return account
    }

    /** The SOLANA record: the count, then the public key of each Solana account, which is an Ed25519 key of 32 bytes. Public data only. */
    fun encodeSolana(keys: List<ByteArray>): ByteArray {
        require(keys.size in 1..PairImport.MAX_SOLANA_ACCOUNTS) { "A wallet holds 1 to ${PairImport.MAX_SOLANA_ACCOUNTS} Solana accounts." }
        require(keys.all { it.size == SOLANA_KEY_BYTES }) { "Not a Solana key." }
        val buffer = ByteBuffer.allocate(2 + keys.size * SOLANA_KEY_BYTES).put(SOLANA_VERSION).put(keys.size.toByte())
        keys.forEach { buffer.put(it) }
        return buffer.array()
    }

    /** The public keys of a SOLANA record, in account order. A record that is not one it can read in full is refused. */
    fun decodeSolana(bytes: ByteArray): List<ByteArray> {
        require(bytes.size >= 2 && bytes[0] == SOLANA_VERSION) { "Not a Solana record." }
        val count = bytes[1].toInt() and UNSIGNED_BYTE_MASK
        require(count in 1..PairImport.MAX_SOLANA_ACCOUNTS && bytes.size == 2 + count * SOLANA_KEY_BYTES) { "Not a Solana record." }
        return List(count) { index -> bytes.copyOfRange(2 + index * SOLANA_KEY_BYTES, 2 + (index + 1) * SOLANA_KEY_BYTES) }
    }

    /** The ALGORAND record: the count, then the public key of each Algorand account, which is an Ed25519 key of 32 bytes. Public data only. */
    fun encodeAlgorand(keys: List<ByteArray>): ByteArray {
        val limit = PairImport.MAX_ALGORAND_ACCOUNTS
        require(keys.size in 1..limit) { "A wallet holds 1 to $limit Algorand accounts." }
        require(keys.all { it.size == ALGORAND_KEY_BYTES }) { "Not an Algorand key." }
        val buffer = ByteBuffer.allocate(2 + keys.size * ALGORAND_KEY_BYTES).put(ALGORAND_VERSION).put(keys.size.toByte())
        keys.forEach { buffer.put(it) }
        return buffer.array()
    }

    /** The public keys of an ALGORAND record, in account order. A record that is not one it can read in full is refused. */
    fun decodeAlgorand(bytes: ByteArray): List<ByteArray> {
        require(bytes.size >= 2 && bytes[0] == ALGORAND_VERSION) { "Not an Algorand record." }
        val count = bytes[1].toInt() and UNSIGNED_BYTE_MASK
        require(count in 1..PairImport.MAX_ALGORAND_ACCOUNTS && bytes.size == 2 + count * ALGORAND_KEY_BYTES) { "Not an Algorand record." }
        return List(count) { index -> bytes.copyOfRange(2 + index * ALGORAND_KEY_BYTES, 2 + (index + 1) * ALGORAND_KEY_BYTES) }
    }

    /** The BITCOIN record: each watch-only Account's network, Script Type, path, compressed public key and chain code. */
    fun encodeBitcoin(accounts: List<BtcWatchOnlyAccount>): ByteArray {
        require(accounts.size in 1..PairImport.MAX_BITCOIN_KEYS) { "A wallet holds 1 to ${PairImport.MAX_BITCOIN_KEYS} Bitcoin accounts." }
        val buffer = ByteBuffer.allocate(2 + accounts.size * BITCOIN_ACCOUNT_BYTES).put(BITCOIN_VERSION).put(accounts.size.toByte())
        for (account in accounts) {
            require(account.path.path.size == ACCOUNT_PATH_LENGTH) { "A Bitcoin account path has three levels." }
            buffer.put(networkCode(account.network))
                .put(scriptCode(account.scriptType))
                .put(ACCOUNT_PATH_LENGTH.toByte())
            account.path.path.forEach { buffer.putInt(it.toInt()) }
            buffer.put(account.xpub.publickeybytes.toByteArray()).put(account.xpub.chaincode.toByteArray())
        }
        return buffer.array()
    }

    /** The Accounts of a BITCOIN record. [fingerprint] is the wallet's master fingerprint from its META record. */
    fun decodeBitcoin(bytes: ByteArray, fingerprint: Long): List<BtcWatchOnlyAccount> {
        require(bytes.size >= 2 && bytes[0] == BITCOIN_VERSION) { "Not a Bitcoin record." }
        val buffer = ByteBuffer.wrap(bytes)
        buffer.get()
        val count = buffer.get().toInt() and UNSIGNED_BYTE_MASK
        require(count in 1..PairImport.MAX_BITCOIN_KEYS && buffer.remaining() == count * BITCOIN_ACCOUNT_BYTES) { "Not a Bitcoin record." }
        return List(count) {
            val network = networkOf(buffer.get())
            val scriptType = scriptTypeOf(buffer.get())
            require(buffer.get().toInt() == ACCOUNT_PATH_LENGTH) { "Not a Bitcoin record." }
            val path = KeyPath(List(ACCOUNT_PATH_LENGTH) { buffer.int.toLong() and UINT32_MASK })
            val publicKey = ByteArray(COMPRESSED_KEY_BYTES).also { buffer.get(it) }
            val chainCode = ByteArray(CHAIN_CODE_BYTES).also { buffer.get(it) }
            val xpub = DeterministicWallet.ExtendedPublicKey(
                publickeybytes = publicKey.byteVector(),
                chaincode = chainCode.byteVector32(),
                depth = ACCOUNT_PATH_LENGTH,
                path = path,
                parent = 0L,
            )
            BtcWatchOnlyAccount(network, scriptType, fingerprint, path, xpub)
        }
    }

    private fun networkCode(network: BtcNetwork): Byte = when (network) {
        BtcNetwork.MAINNET -> 0
        BtcNetwork.TESTNET4 -> 1
    }

    private fun networkOf(code: Byte): BtcNetwork = when (code.toInt()) {
        0 -> BtcNetwork.MAINNET
        1 -> BtcNetwork.TESTNET4
        else -> throw IllegalArgumentException("Not a Bitcoin record.")
    }

    private fun scriptCode(scriptType: ScriptType): Byte = when (scriptType) {
        ScriptType.LEGACY -> 0
        ScriptType.NESTED_SEGWIT -> 1
        ScriptType.NATIVE_SEGWIT -> 2
        ScriptType.TAPROOT -> 3
    }

    private fun scriptTypeOf(code: Byte): ScriptType = when (code.toInt()) {
        0 -> ScriptType.LEGACY
        1 -> ScriptType.NESTED_SEGWIT
        2 -> ScriptType.NATIVE_SEGWIT
        3 -> ScriptType.TAPROOT
        else -> throw IllegalArgumentException("Not a Bitcoin record.")
    }
}
