package app.lumio.pair

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
 * The bytes of one paired wallet's records, as Lumio writes them into a wallet folder. Each record starts with a version byte, then fixed
 * fields in big-endian order. A record holds public data only: a fingerprint, a name, public keys, chain codes and paths. Every decoder
 * rejects a record it cannot read in full, so a damaged record reads as no pairing.
 */
internal object PairRecords {
    private const val VERSION: Byte = 1
    private const val PUBLIC_KEY_BYTES = 33
    private const val CHAIN_CODE_BYTES = 32
    private const val FINGERPRINT_BYTES = 4
    private const val META_HEADER_BYTES = 14
    private const val EVM_BYTES = 1 + PUBLIC_KEY_BYTES + CHAIN_CODE_BYTES + FINGERPRINT_BYTES
    private const val BITCOIN_ACCOUNT_HEADER_BYTES = 3
    private const val BITCOIN_ACCOUNT_TAIL_BYTES = 1 + FINGERPRINT_BYTES + PUBLIC_KEY_BYTES + CHAIN_CODE_BYTES
    private const val ACCOUNT_PATH_LENGTH = 3
    private const val UINT32_MASK = 0xFFFFFFFFL
    private const val UNSIGNED_BYTE_MASK = 0xFF

    /** The META record: the wallet's master fingerprint, when Lumio first paired it, and its name. */
    class Meta(val fingerprint: Long, val createdAtMs: Long, val name: String)

    fun encodeMeta(meta: Meta): ByteArray {
        val name = meta.name.toByteArray(Charsets.UTF_8)
        require(name.size <= PairImport.MAX_NAME_BYTES) { "A wallet name holds at most ${PairImport.MAX_NAME_BYTES} bytes." }
        return ByteBuffer.allocate(META_HEADER_BYTES + name.size)
            .put(VERSION)
            .putInt(meta.fingerprint.toInt())
            .putLong(meta.createdAtMs)
            .put(name.size.toByte())
            .put(name)
            .array()
    }

    fun decodeMeta(bytes: ByteArray): Meta {
        require(bytes.size >= META_HEADER_BYTES && bytes[0] == VERSION) { "Not a pairing record." }
        val buffer = ByteBuffer.wrap(bytes)
        buffer.get()
        val fingerprint = buffer.int.toLong() and UINT32_MASK
        val createdAtMs = buffer.long
        val length = buffer.get().toInt() and UNSIGNED_BYTE_MASK
        require(length <= PairImport.MAX_NAME_BYTES && buffer.remaining() == length) { "Not a pairing record." }
        val name = String(bytes, buffer.position(), length, Charsets.UTF_8)
        return Meta(fingerprint, createdAtMs, name)
    }

    /** The EVM record: the account key m/44'/60'/0' with its chain code and parent fingerprint. */
    fun encodeEvm(xpub: AccountXpub): ByteArray {
        require(xpub.publicKey.size == PUBLIC_KEY_BYTES && xpub.chainCode.size == CHAIN_CODE_BYTES) { "Not an account key." }
        return ByteBuffer.allocate(EVM_BYTES)
            .put(VERSION)
            .put(xpub.publicKey)
            .put(xpub.chainCode)
            .putInt(xpub.parentFingerprint)
            .array()
    }

    fun decodeEvm(bytes: ByteArray): AccountXpub {
        require(bytes.size == EVM_BYTES && bytes[0] == VERSION) { "Not an account key record." }
        val buffer = ByteBuffer.wrap(bytes, 1, EVM_BYTES - 1)
        val publicKey = ByteArray(PUBLIC_KEY_BYTES).also { buffer.get(it) }
        val chainCode = ByteArray(CHAIN_CODE_BYTES).also { buffer.get(it) }
        return AccountXpub(publicKey = publicKey, chainCode = chainCode, parentFingerprint = buffer.int)
    }

    /** The BITCOIN record: each watch-only Account's network, Script Type, path and extended public key. */
    fun encodeBitcoin(accounts: List<BtcWatchOnlyAccount>): ByteArray {
        require(accounts.size in 1..PairImport.MAX_BITCOIN_ACCOUNTS) { "A pairing holds 1 to ${PairImport.MAX_BITCOIN_ACCOUNTS} accounts." }
        val size = 2 + accounts.size * (BITCOIN_ACCOUNT_HEADER_BYTES + ACCOUNT_PATH_LENGTH * FINGERPRINT_BYTES + BITCOIN_ACCOUNT_TAIL_BYTES)
        val buffer = ByteBuffer.allocate(size).put(VERSION).put(accounts.size.toByte())
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
        require(bytes.size >= 2 && bytes[0] == VERSION) { "Not a Bitcoin record." }
        val buffer = ByteBuffer.wrap(bytes)
        buffer.get()
        val count = buffer.get().toInt() and UNSIGNED_BYTE_MASK
        require(count in 1..PairImport.MAX_BITCOIN_ACCOUNTS) { "Not a Bitcoin record." }
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
