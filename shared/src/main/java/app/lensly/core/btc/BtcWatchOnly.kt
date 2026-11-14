package app.lensly.core.btc

import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.PRIVATE_KEY_REFUSAL
import app.lensly.core.ur.framesOf
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoKeypath
import com.sparrowwallet.hummingbird.registry.CryptoOutput
import com.sparrowwallet.hummingbird.registry.ScriptExpression
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import com.sparrowwallet.hummingbird.registry.pathcomponent.PathComponent
import fr.acinq.bitcoin.Descriptor
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.byteVector
import fr.acinq.bitcoin.byteVector32
import java.nio.ByteBuffer

/**
 * The watch-only half of a Bitcoin Account: the account xpub, the master fingerprint and the path it sits on. An online
 * wallet holding this can watch every address of the Account and build requests, but it cannot sign.
 */
data class BtcWatchOnlyAccount(
    val network: BtcNetwork,
    val scriptType: ScriptType,
    val masterFingerprint: Long,
    val path: KeyPath,
    val xpub: DeterministicWallet.ExtendedPublicKey,
) {
    /**
     * The output descriptor, with its BIP380 checksum. One descriptor covers both branches through the multipath form, which
     * names the branch as <0;1> and the index as a wildcard, so the receive and change chains come from one string.
     */
    val descriptor: String = run {
        val key = "[%08x/%s]%s/<0;1>/*".format(
            masterFingerprint,
            path.asString('\'').removePrefix("m/"),
            xpub.encode(network.xpubVersion),
        )
        val body = when (scriptType) {
            ScriptType.LEGACY -> "pkh($key)"
            ScriptType.NESTED_SEGWIT -> "sh(wpkh($key))"
            ScriptType.NATIVE_SEGWIT -> "wpkh($key)"
            ScriptType.TAPROOT -> "tr($key)"
        }
        "$body#${Descriptor.checksum(body)}"
    }

    /**
     * The receive address at m/.../0/[index], derived from the public key alone. A watch-only copy shows this address without the seed.
     * @throws IllegalArgumentException if [index] is negative.
     */
    fun receiveAddress(index: Int): String {
        require(index >= 0) { "address index must be 0 or more" }
        return addressOf(scriptType, network, xpub.derivePublicKey(0).derivePublicKey(index.toLong()).publicKey)
    }
}

/**
 * The Watch-Only export of a seed: one [BtcWatchOnlyAccount] per [ScriptType], carried as a `ur:crypto-account` with a
 * crypto-output for each Script Type (BCR-2020-015). Wallets that import either form can watch the Accounts.
 */
object BtcWatchOnly {
    /** The four watch-only Accounts at [accountIndex] of [network], derived from a BIP39 [seed] of 16 to 64 bytes. */
    fun accounts(seed: ByteArray, network: BtcNetwork, accountIndex: Int = 0): List<BtcWatchOnlyAccount> =
        ScriptType.entries.map { BtcAccount.fromSeed(seed, network, it, accountIndex).watchOnly() }

    /** The `ur:crypto-account` that carries [accounts], which must share one master fingerprint. */
    fun cryptoAccount(accounts: List<BtcWatchOnlyAccount>): String = UREncoder.encode(cryptoAccountUr(accounts))

    /**
     * The frames of the `ur:crypto-account` that carries [accounts]. A payload that fits one QR code is one frame. A larger one
     * gives the uncoded parts of an animated code, which the encoder emits first, so a caller that cycles this list sends the whole
     * payload in every pass.
     */
    fun cryptoAccountParts(accounts: List<BtcWatchOnlyAccount>): List<String> {
        val frames = framesOf(cryptoAccountUr(accounts), DEFAULT_MAX_FRAGMENT)
        return frames.take(sequenceLength(frames.first())).toList()
    }

    private fun cryptoAccountUr(accounts: List<BtcWatchOnlyAccount>): UR {
        require(accounts.isNotEmpty()) { "no accounts to export" }
        require(accounts.map { it.masterFingerprint }.distinct().size == 1) { "accounts must share one master fingerprint" }
        val outputs = accounts.map { CryptoOutput(it.scriptType.expressions(), it.hdKey()) }
        return CryptoAccount(accounts.first().masterFingerprint.fingerprintBytes(), outputs).toUR()
    }

    /** The Accounts carried by a `ur:crypto-account`. Throws [IllegalArgumentException] for any other input. */
    fun parseCryptoAccount(ur: String): List<BtcWatchOnlyAccount> {
        val decoded = try {
            URDecoder.decode(ur)
        } catch (e: Exception) {
            throw IllegalArgumentException("not a ur:crypto-account", e)
        }
        return parseCryptoAccount(decoded)
    }

    /**
     * The Accounts carried by a `ur:crypto-account` that a scanner has already read. A crypto-output that holds private key material is
     * refused, so only public data comes out. Throws [IllegalArgumentException] for any other input.
     */
    fun parseCryptoAccount(ur: UR): List<BtcWatchOnlyAccount> {
        val decoded = try {
            ur.decodeFromRegistry()
        } catch (e: Exception) {
            throw IllegalArgumentException("not a ur:crypto-account", e)
        }
        val account = decoded as? CryptoAccount ?: throw IllegalArgumentException("not a ur:crypto-account")
        val fingerprint = ByteBuffer.wrap(account.masterFingerprint).int.toLong() and UINT32_MASK
        return account.outputDescriptors.map { it.toWatchOnly(fingerprint) }
    }
}

private const val UINT32_MASK = 0xFFFFFFFFL
private const val PRIVATE_KEY_BYTES = 32
private const val COMPRESSED_PUBLIC_KEY_BYTES = 33

/** The "i-n" sequence header that every frame of a multi-part UR carries after its type, as in ur:type/1-3/body. */
private val SEQUENCE_HEADER = Regex("""^UR:[A-Z0-9-]+/\d+-(\d+)/""", RegexOption.IGNORE_CASE)

/** The number of parts an animated UR has, read from the sequence header of one of its frames, or 1 for a single-part UR. */
internal fun sequenceLength(frame: String): Int = SEQUENCE_HEADER.find(frame)?.groupValues?.get(1)?.toInt() ?: 1

private fun BtcWatchOnlyAccount.hdKey(): CryptoHDKey {
    val origin = CryptoKeypath(path.components(), masterFingerprint.fingerprintBytes(), path.path.size)
    // Key 3 is the public key and key 4 the chain code. The parent fingerprint is key 8, and the name and note stay absent.
    return CryptoHDKey(
        null,
        xpub.publickeybytes.toByteArray(),
        xpub.chaincode.toByteArray(),
        null,
        origin,
        null,
        xpub.parent.fingerprintBytes(),
        null,
        null,
    )
}

private fun CryptoOutput.toWatchOnly(fingerprint: Long): BtcWatchOnlyAccount {
    val scriptType = ScriptType.entries.firstOrNull { it.expressions() == scriptExpressions }
        ?: throw IllegalArgumentException("unsupported script expressions in crypto-output")
    val key = hdKey ?: throw IllegalArgumentException("crypto-output without a crypto-hdkey")
    // A watch-only key is a 33-byte compressed public key. A private key is refused, whether or not the code marks it as one.
    if (key.isPrivateKey || key.key.size == PRIVATE_KEY_BYTES) throw IllegalArgumentException(PRIVATE_KEY_REFUSAL)
    require(key.key.size == COMPRESSED_PUBLIC_KEY_BYTES) { "crypto-hdkey key must be a 33-byte compressed public key" }
    val origin = key.origin ?: throw IllegalArgumentException("crypto-hdkey without an origin path")
    val path = KeyPath(origin.components.map { component ->
        val index = component as? IndexPathComponent
            ?: throw IllegalArgumentException("origin path must contain index components only")
        if (index.isHardened) DeterministicWallet.hardened(index.index.toLong()) else index.index.toLong()
    })
    require(origin.depth == path.path.size) { "origin depth does not match its path" }
    val coin = path.path.getOrNull(1)?.let { it - DeterministicWallet.hardenedKeyIndex }
    val network = BtcNetwork.entries.firstOrNull { it.coinType.toLong() == coin }
        ?: throw IllegalArgumentException("origin path is not a Bitcoin account path")
    val xpub = DeterministicWallet.ExtendedPublicKey(
        publickeybytes = key.key.byteVector(),
        chaincode = key.chainCode.byteVector32(),
        depth = path.path.size,
        path = path,
        parent = ByteBuffer.wrap(key.parentFingerprint).int.toLong() and UINT32_MASK,
    )
    return BtcWatchOnlyAccount(network, scriptType, fingerprint, path, xpub)
}

/** The script expressions that name each Script Type in a crypto-output, as BCR-2020-015 writes them. */
private fun ScriptType.expressions(): List<ScriptExpression> = when (this) {
    ScriptType.LEGACY -> listOf(ScriptExpression.PUBLIC_KEY_HASH)
    ScriptType.NESTED_SEGWIT -> listOf(ScriptExpression.SCRIPT_HASH, ScriptExpression.WITNESS_PUBLIC_KEY_HASH)
    ScriptType.NATIVE_SEGWIT -> listOf(ScriptExpression.WITNESS_PUBLIC_KEY_HASH)
    ScriptType.TAPROOT -> listOf(ScriptExpression.TAPROOT)
}

/** The origin components of an account path: hardened levels keep their index and carry the hardened flag. */
private fun KeyPath.components(): List<PathComponent> = path.map { child ->
    val hardened = DeterministicWallet.isHardened(child)
    IndexPathComponent(
        (if (hardened) child - DeterministicWallet.hardenedKeyIndex else child).toInt(),
        hardened,
    )
}

/** A master fingerprint or parent fingerprint as the 4 big-endian bytes the registry stores. */
private fun Long.fingerprintBytes(): ByteArray = ByteBuffer.allocate(4).putInt(toInt()).array()
