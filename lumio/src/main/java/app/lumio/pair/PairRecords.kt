package app.lumio.pair

import app.lensly.app.export.PairingCodec
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.btc.ScriptType
import app.lensly.core.wallet.AccountXpub
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.byteVector
import fr.acinq.bitcoin.byteVector32
import java.nio.ByteBuffer

/**
 * The bytes of one paired wallet's records, as Lumio writes them into a wallet folder. Each record starts with a version byte. The META
 * and account records hold length-prefixed text and fixed fields in big-endian order, and the Bitcoin record holds the watch-only Accounts.
 * A record holds public data only. Every decoder rejects a record it cannot read in full, so a damaged record reads as no pairing.
 */
internal object PairRecords {
    /** The layout of the META and account records. A record of an older layout is not read as a pairing. */
    private const val VERSION: Byte = 2

    /** The layout of the Bitcoin record. */
    private const val BITCOIN_VERSION: Byte = 1
    private const val PUBLIC_KEY_BYTES = 33
    private const val CHAIN_CODE_BYTES = 32
    private const val FINGERPRINT_BYTES = 4
    private const val UINT32_MASK = 0xFFFFFFFFL
    private const val UNSIGNED_BYTE_MASK = 0xFF
    private const val ACCOUNT_PATH_LENGTH = 3
    private const val BITCOIN_ACCOUNT_HEADER_BYTES = 3
    private const val BITCOIN_ACCOUNT_TAIL_BYTES = 1 + FINGERPRINT_BYTES + PUBLIC_KEY_BYTES + CHAIN_CODE_BYTES

    /** The META record's fixed part: the version, the fingerprint, the creation time and the name's length. */
    private const val META_HEADER_BYTES = 1 + FINGERPRINT_BYTES + 8 + 1

    /** The META record's key part: the number of Ethereum accounts, the account key's public key, chain code and parent fingerprint. */
    private const val META_KEY_BYTES = 1 + PUBLIC_KEY_BYTES + CHAIN_CODE_BYTES + FINGERPRINT_BYTES

    /**
     * The META record: the wallet's master fingerprint, when Lumio first paired it, its creation time, its name, the number of its Ethereum
     * accounts, and its Ethereum account key m/44'/60'/0'.
     */
    class Meta(val fingerprint: Long, val createdAtMs: Long, val name: String, val accountCount: Int, val evm: AccountXpub)

    fun encodeMeta(meta: Meta): ByteArray {
        val name = meta.name.toByteArray(Charsets.UTF_8)
        require(name.size in 1..PairingCodec.MAX_NAME_BYTES) { "A wallet name holds 1 to ${PairingCodec.MAX_NAME_BYTES} bytes." }
        require(meta.accountCount in 1..PairImport.MAX_ETHEREUM_ACCOUNTS) { "A wallet holds 1 to ${PairImport.MAX_ETHEREUM_ACCOUNTS} accounts." }
        require(meta.evm.publicKey.size == PUBLIC_KEY_BYTES && meta.evm.chainCode.size == CHAIN_CODE_BYTES) { "Not an account key." }
        return ByteBuffer.allocate(META_HEADER_BYTES + name.size + META_KEY_BYTES)
            .put(VERSION)
            .putInt(meta.fingerprint.toInt())
            .putLong(meta.createdAtMs)
            .put(name.size.toByte())
            .put(name)
            .put(meta.accountCount.toByte())
            .put(meta.evm.publicKey)
            .put(meta.evm.chainCode)
            .putInt(meta.evm.parentFingerprint)
            .array()
    }

    fun decodeMeta(bytes: ByteArray): Meta {
        require(bytes.size > META_HEADER_BYTES && bytes[0] == VERSION) { "Not a pairing record." }
        val buffer = ByteBuffer.wrap(bytes)
        buffer.get()
        val fingerprint = buffer.int.toLong() and UINT32_MASK
        val createdAtMs = buffer.long
        val length = buffer.get().toInt() and UNSIGNED_BYTE_MASK
        require(length in 1..PairingCodec.MAX_NAME_BYTES && bytes.size == META_HEADER_BYTES + length + META_KEY_BYTES) {
            "Not a pairing record."
        }
        val name = String(bytes, buffer.position(), length, Charsets.UTF_8)
        buffer.position(buffer.position() + length)
        val accountCount = buffer.get().toInt() and UNSIGNED_BYTE_MASK
        require(accountCount in 1..PairImport.MAX_ETHEREUM_ACCOUNTS) { "Not a pairing record." }
        val publicKey = ByteArray(PUBLIC_KEY_BYTES).also { buffer.get(it) }
        val chainCode = ByteArray(CHAIN_CODE_BYTES).also { buffer.get(it) }
        val parent = buffer.int
        return Meta(fingerprint, createdAtMs, name, accountCount, AccountXpub(publicKey, chainCode, parent))
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

    /** The BITCOIN record: each watch-only Account's network, Script Type, path and extended public key. */
    fun encodeBitcoin(accounts: List<BtcWatchOnlyAccount>): ByteArray {
        require(accounts.size in 1..PairImport.MAX_BITCOIN_KEYS) { "A wallet holds 1 to ${PairImport.MAX_BITCOIN_KEYS} Bitcoin accounts." }
        val size = 2 + accounts.size * (BITCOIN_ACCOUNT_HEADER_BYTES + ACCOUNT_PATH_LENGTH * FINGERPRINT_BYTES + BITCOIN_ACCOUNT_TAIL_BYTES)
        val buffer = ByteBuffer.allocate(size).put(BITCOIN_VERSION).put(accounts.size.toByte())
        for (account in accounts) {
            require(account.path.path.size == ACCOUNT_PATH_LENGTH) { "A Bitcoin account path has three levels." }
            buffer.put(networkCode(account.network))
                .put(scriptCode(account.scriptType))
                .put(ACCOUNT_PATH_LENGTH.toByte())
            account.path.path.forEach { buffer.putInt(it.toInt()) }
            buffer.put(account.xpub.depth.toByte())
                .putInt(account.xpub.parent.toInt())
                .put(account.xpub.publickeybytes.toByteArray())
                .put(account.xpub.chaincode.toByteArray())
        }
        return buffer.array()
    }

    /** The Accounts of a BITCOIN record. [fingerprint] is the wallet's master fingerprint from its META record. */
    fun decodeBitcoin(bytes: ByteArray, fingerprint: Long): List<BtcWatchOnlyAccount> {
        require(bytes.size >= 2 && bytes[0] == BITCOIN_VERSION) { "Not a Bitcoin record." }
        val buffer = ByteBuffer.wrap(bytes)
        buffer.get()
        val count = buffer.get().toInt() and UNSIGNED_BYTE_MASK
        require(count in 1..PairImport.MAX_BITCOIN_KEYS) { "Not a Bitcoin record." }
        val accounts = List(count) {
            val network = networkOf(buffer.get())
            val scriptType = scriptTypeOf(buffer.get())
            require(buffer.get().toInt() == ACCOUNT_PATH_LENGTH) { "Not a Bitcoin record." }
            val path = KeyPath(List(ACCOUNT_PATH_LENGTH) { buffer.int.toLong() and UINT32_MASK })
            val depth = buffer.get().toInt() and UNSIGNED_BYTE_MASK
            val parent = buffer.int.toLong() and UINT32_MASK
            val publicKey = ByteArray(PUBLIC_KEY_BYTES).also { buffer.get(it) }
            val chainCode = ByteArray(CHAIN_CODE_BYTES).also { buffer.get(it) }
            val xpub = DeterministicWallet.ExtendedPublicKey(
                publickeybytes = publicKey.byteVector(),
                chaincode = chainCode.byteVector32(),
                depth = depth,
                path = path,
                parent = parent,
            )
            BtcWatchOnlyAccount(network, scriptType, fingerprint, path, xpub)
        }
        require(!buffer.hasRemaining()) { "Not a Bitcoin record." }
        return accounts
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
