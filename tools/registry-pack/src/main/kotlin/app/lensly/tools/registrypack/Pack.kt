package app.lensly.tools.registrypack

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** A registry package's content: the entries file and the signed payload share this shape. */
@Serializable
data class PackageDto(
    val formatVersion: Int,
    /** The catalog schema the package needs. The app refuses a package that needs a schema newer than it reads. */
    val minSchemaVersion: Int = 1,
    val sequence: Long,
    val createdAt: String,
    val networks: List<NetworkDto>,
    val tokens: List<TokenDto>,
    /** Optional. A package made before the whitelist existed has none, and the app then has an empty whitelist. */
    val whitelist: List<WhitelistDto> = emptyList(),
    /** Optional. Omitted, the app keeps its built-in denylist. An empty list clears it. Needs minSchemaVersion 2. */
    val denylist: List<DenylistDto>? = null,
    /** Optional. Omitted, the app keeps its built-in wallets. An empty list clears them. Needs minSchemaVersion 2. */
    val wallets: List<WalletDto>? = null,
)

/**
 * One known-dangerous request. It names a selector, a contract on a chain, or both, or a typed-data [primaryType]. [condition] narrows
 * a selector to the risky calls: UNLIMITED for an unlimited amount, APPROVED for an operator grant of true.
 */
@Serializable
data class DenylistDto(
    val chainId: Long? = null,
    val contract: String? = null,
    val selector: String? = null,
    val primaryType: String? = null,
    val condition: DenylistCondition = DenylistCondition.ANY,
    val severity: DenylistSeverity,
    val reason: String,
)

@Serializable
enum class DenylistCondition { ANY, UNLIMITED, APPROVED }

/** WARN asks the owner to confirm; BLOCK refuses the request and shows its reason. */
@Serializable
enum class DenylistSeverity { WARN, BLOCK }

/** One online wallet of the Wallet Compatibility Registry, with the code formats the vault reads and writes for it. */
@Serializable
data class WalletDto(
    val name: String,
    val ecosystems: List<String>,
    val formats: List<String>,
    val note: String,
    val limitations: String,
)

/** One contract call the whitelist approves. [selector] must be the Keccak-256 selector of [signature]. */
@Serializable
data class WhitelistDto(
    val chainId: Long,
    val contract: String,
    val selector: String,
    val signature: String,
    val meaning: String,
    val proxy: ProxyDto? = null,
    /** The arguments of the call, in signature order. Their count and types must equal the signature's. */
    val params: List<ParamDto>,
    /** True when the target is an upgradeable proxy, which must then name its [proxy] binding. */
    val upgradeable: Boolean,
)

/** One static argument of a whitelisted call: its name, its static ABI type, and whether it is a token amount. */
@Serializable
data class ParamDto(
    val name: String,
    val type: String,
    val tokenRef: Boolean = false,
)

/** The implementation a whitelisted proxy is bound to: its address, its code hash, or both. */
@Serializable
data class ProxyDto(
    val implementationAddress: String? = null,
    val implementationCodeHash: String? = null,
)

@Serializable
data class NetworkDto(
    val chainId: Long,
    val name: String,
    val nativeSymbol: String,
    val nativeDecimals: Int,
    val feeModel: FeeModel,
    val explorerName: String?,
    val testnet: Boolean,
)

@Serializable
data class TokenDto(
    val chainId: Long,
    val contract: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
)

@Serializable
enum class FeeModel { L1, L2_ROLLUP }

/**
 * Builds, checks and signs registry packages. The app reader is `core/registry/update`; the two must agree.
 *
 * File: `LRP2` | u32 big-endian payload length | 8-byte key ID | payload | 64-byte Ed25519 signature.
 * Key ID: the first 8 bytes of the SHA-256 of the signing public key. The app picks its trusted key by this ID.
 * Payload: canonical JSON (sorted keys, no whitespace, entries sorted by chain ID and then contract). The `whitelist` key
 * is written only when the package has whitelist entries, so a package without one keeps the bytes it had before. The
 * `denylist` and `wallets` keys are written whenever the source has them, even when empty, because an omitted list means
 * "keep the app's own" and an empty one means "clear it".
 * Signed message: `LENSLY-REGISTRY-v2\n`, the key ID, then the payload bytes.
 */
internal object Pack {
    const val FORMAT_VERSION = 2
    /** The newest catalog schema the app reads. A package may not need a newer one. */
    const val SCHEMA_VERSION = 2
    const val MAX_BYTES = 1 shl 20

    private val MAGIC = "LRP2".toByteArray(StandardCharsets.US_ASCII)
    private val DOMAIN = "LENSLY-REGISTRY-v2\n".toByteArray(StandardCharsets.US_ASCII)
    private const val KEY_ID_OFFSET = 8
    private const val KEY_ID_BYTES = 8
    private const val HEADER_BYTES = KEY_ID_OFFSET + KEY_ID_BYTES
    private const val SIGNATURE_BYTES = 64
    private const val MAX_TEXT = 64
    private const val MAX_MEANING = 240
    private const val MAX_DECIMALS = 36
    private val CONTRACT = Regex("0x[0-9a-f]{40}")
    private val SELECTOR = Regex("0x[0-9a-f]{8}")
    private val CODE_HASH = Regex("0x[0-9a-f]{64}")
    private val SIGNATURE = Regex("[A-Za-z_][A-Za-z0-9_]*\\((?:[a-z0-9\\[\\]]+(?:,[a-z0-9\\[\\]]+)*)?\\)")
    private val INTEGER_TYPES = (8..256 step 8).flatMap { listOf("uint$it", "int$it") }.toSet()
    private val BYTES_TYPES = (1..32).map { "bytes$it" }.toSet()
    private val json = Json

    /** The ABI types of [signature], in order. */
    private fun signatureTypes(signature: String): List<String> =
        signature.substringAfter('(').substringBefore(')').split(',').filter { it.isNotEmpty() }

    /** True for an ABI type whose value is one 32-byte word. */
    private fun isStaticType(type: String): Boolean =
        type == "address" || type == "bool" || type in INTEGER_TYPES || type in BYTES_TYPES

    /** Builds a signed package from entries JSON. The same entries and seed always give the same bytes. */
    fun build(entriesJson: String, seed: ByteArray): Pair<ByteArray, PackageDto> {
        val content = json.decodeFromString<PackageDto>(entriesJson)
        validate(content)
        val bytes = encode(canonicalPayload(content), seed)
        require(bytes.size <= MAX_BYTES) { "package is ${bytes.size} bytes; the limit is $MAX_BYTES" }
        verify(bytes, publicKeyOf(seed)) // refuse to write a package that does not read back
        return bytes to content
    }

    /** Checks the structure, signature, content and canonical form of [bytes], and returns its content. */
    fun verify(bytes: ByteArray, publicKey: ByteArray): PackageDto {
        val payload = open(bytes, publicKey)
        val content = json.decodeFromString<PackageDto>(String(payload, StandardCharsets.UTF_8))
        validate(content)
        require(canonicalPayload(content).contentEquals(payload)) { "payload is not in canonical form" }
        return content
    }

    fun publicKeyOf(seed: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    /** The key ID of [publicKey], as the app derives it: the first [KEY_ID_BYTES] bytes of its SHA-256. */
    fun keyIdOf(publicKey: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(KEY_ID_BYTES)

    /** Canonical payload bytes for [content]. Entries are sorted here, so the order in the source never matters. */
    fun canonicalPayload(content: PackageDto): ByteArray = CanonicalJson.write(
        buildMap {
            put("formatVersion", content.formatVersion)
            put("minSchemaVersion", content.minSchemaVersion)
            put("sequence", content.sequence)
            put("createdAt", content.createdAt)
            put("networks", content.networks.sortedBy { it.chainId }.map(::networkMap))
            put("tokens", content.tokens.sortedWith(compareBy<TokenDto>({ it.chainId }, { it.contract })).map(::tokenMap))
            if (content.whitelist.isNotEmpty()) {
                put("whitelist", content.whitelist.sortedWith(WHITELIST_ORDER).map(::whitelistMap))
            }
            content.denylist?.let { put("denylist", it.sortedWith(DENYLIST_ORDER).map(::denylistMap)) }
            content.wallets?.let { put("wallets", it.sortedBy { wallet -> wallet.name }.map(::walletMap)) }
        },
    ).toByteArray(StandardCharsets.UTF_8)

    private val WHITELIST_ORDER = compareBy<WhitelistDto>({ it.chainId }, { it.contract }, { it.selector })

    private val DENYLIST_ORDER = compareBy<DenylistDto>(
        { it.chainId ?: 0L },
        { it.contract.orEmpty() },
        { it.selector.orEmpty() },
        { it.primaryType.orEmpty() },
        { it.condition.name },
        { it.severity.name },
        { it.reason },
    )

    private fun denylistMap(entry: DenylistDto): Map<String, Any?> = mapOf(
        "chainId" to entry.chainId,
        "condition" to entry.condition.name,
        "contract" to entry.contract,
        "primaryType" to entry.primaryType,
        "reason" to entry.reason,
        "selector" to entry.selector,
        "severity" to entry.severity.name,
    )

    private fun walletMap(wallet: WalletDto): Map<String, Any?> = mapOf(
        "ecosystems" to wallet.ecosystems,
        "formats" to wallet.formats,
        "limitations" to wallet.limitations,
        "name" to wallet.name,
        "note" to wallet.note,
    )

    private fun whitelistMap(entry: WhitelistDto): Map<String, Any?> = mapOf(
        "chainId" to entry.chainId,
        "contract" to entry.contract,
        "meaning" to entry.meaning,
        "params" to entry.params.map {
            mapOf("name" to it.name, "tokenRef" to it.tokenRef, "type" to it.type)
        },
        "proxy" to entry.proxy?.let {
            mapOf("implementationAddress" to it.implementationAddress, "implementationCodeHash" to it.implementationCodeHash)
        },
        "selector" to entry.selector,
        "signature" to entry.signature,
        "upgradeable" to entry.upgradeable,
    )

    private fun networkMap(network: NetworkDto): Map<String, Any?> = mapOf(
        "chainId" to network.chainId,
        "explorerName" to network.explorerName,
        "feeModel" to network.feeModel.name,
        "name" to network.name,
        "nativeDecimals" to network.nativeDecimals,
        "nativeSymbol" to network.nativeSymbol,
        "testnet" to network.testnet,
    )

    private fun tokenMap(token: TokenDto): Map<String, Any?> = mapOf(
        "chainId" to token.chainId,
        "contract" to token.contract,
        "decimals" to token.decimals,
        "name" to token.name,
        "symbol" to token.symbol,
    )

    private fun validate(content: PackageDto) {
        require(content.formatVersion == FORMAT_VERSION) { "formatVersion must be $FORMAT_VERSION" }
        require(content.minSchemaVersion in 1..SCHEMA_VERSION) { "minSchemaVersion must be 1 to $SCHEMA_VERSION" }
        require(content.sequence >= 1) { "sequence must be at least 1" }
        try {
            LocalDate.parse(content.createdAt)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("createdAt must be an ISO date (yyyy-mm-dd): ${content.createdAt}")
        }
        require(content.networks.map { it.chainId }.distinct().size == content.networks.size) {
            "a network chainId appears more than once"
        }
        for (network in content.networks) {
            require(network.chainId > 0) { "network chainId must be positive: ${network.chainId}" }
            // Release builds never see a testnet (RegistryLoader), so a package cannot add one.
            require(!network.testnet) { "network ${network.chainId}: a package cannot add a testnet" }
            requireText("network ${network.chainId} name", network.name)
            requireText("network ${network.chainId} nativeSymbol", network.nativeSymbol)
            require(network.nativeDecimals in 0..MAX_DECIMALS) {
                "network ${network.chainId} nativeDecimals must be 0 to $MAX_DECIMALS"
            }
            network.explorerName?.let { requireText("network ${network.chainId} explorerName", it) }
        }
        require(content.tokens.map { it.chainId to it.contract }.distinct().size == content.tokens.size) {
            "a token appears more than once"
        }
        for (token in content.tokens) {
            require(token.chainId > 0) { "token chainId must be positive: ${token.chainId}" }
            require(CONTRACT.matches(token.contract)) {
                "token contract must be 0x followed by 40 lowercase hex digits: ${token.contract}"
            }
            requireText("token ${token.contract} symbol", token.symbol)
            requireText("token ${token.contract} name", token.name)
            require(token.decimals in 0..MAX_DECIMALS) {
                "token ${token.contract} decimals must be 0 to $MAX_DECIMALS"
            }
        }
        val approvals = content.whitelist.map { Triple(it.chainId, it.contract, it.selector) }
        require(approvals.distinct().size == approvals.size) {
            "a whitelist entry appears more than once (same chainId, contract and selector)"
        }
        for (entry in content.whitelist) {
            require(entry.chainId > 0) { "whitelist chainId must be positive: ${entry.chainId}" }
            require(CONTRACT.matches(entry.contract)) {
                "whitelist contract must be 0x followed by 40 lowercase hex digits: ${entry.contract}"
            }
            require(SIGNATURE.matches(entry.signature)) {
                "whitelist signature must look like name(type,type): ${entry.signature}"
            }
            require(SELECTOR.matches(entry.selector) && entry.selector == selectorOf(entry.signature)) {
                "whitelist selector ${entry.selector} is not the Keccak-256 selector of ${entry.signature}"
            }
            requireText("whitelist ${entry.signature} meaning", entry.meaning, MAX_MEANING)
            val types = signatureTypes(entry.signature)
            require(entry.params.size == types.size) {
                "whitelist ${entry.signature}: params must name every argument"
            }
            entry.params.forEachIndexed { index, param ->
                requireText("whitelist ${entry.signature} param name", param.name)
                require(param.type == types[index]) {
                    "whitelist ${entry.signature}: param ${param.name} must be ${types[index]}"
                }
                require(isStaticType(param.type)) {
                    "whitelist ${entry.signature}: param type must be a static ABI type: ${param.type}"
                }
            }
            require(!entry.upgradeable || entry.proxy != null) {
                "whitelist ${entry.signature}: an upgradeable entry must name its proxy binding"
            }
            require(entry.upgradeable || entry.proxy == null) {
                "whitelist ${entry.signature}: a proxy binding belongs to an upgradeable entry"
            }
            entry.proxy?.let { proxy ->
                require(proxy.implementationAddress != null || proxy.implementationCodeHash != null) {
                    "whitelist ${entry.signature}: a proxy names its implementation, its code hash, or both"
                }
                proxy.implementationAddress?.let {
                    require(CONTRACT.matches(it)) {
                        "implementation address must be 0x followed by 40 lowercase hex digits: $it"
                    }
                }
                proxy.implementationCodeHash?.let {
                    require(CODE_HASH.matches(it)) {
                        "implementation code hash must be 0x followed by 64 lowercase hex digits"
                    }
                }
            }
        }
        val newLists = content.denylist != null || content.wallets != null
        require(!newLists || content.minSchemaVersion >= 2) { "a denylist or wallets list needs minSchemaVersion 2" }
        content.denylist?.forEachIndexed { index, entry -> validateDenylist(index + 1, entry) }
        content.wallets?.let { wallets ->
            require(wallets.map { it.name }.distinct().size == wallets.size) { "a wallet appears more than once (same name)" }
            wallets.forEach(::validateWallet)
        }
    }

    private fun validateDenylist(number: Int, entry: DenylistDto) {
        val what = "denylist entry $number"
        entry.chainId?.let { require(it > 0) { "$what: chainId must be positive: $it" } }
        entry.contract?.let {
            require(entry.chainId != null) { "$what: a contract needs a chainId" }
            require(CONTRACT.matches(it)) { "$what: contract must be 0x followed by 40 lowercase hex digits: $it" }
        }
        entry.selector?.let {
            require(SELECTOR.matches(it)) { "$what: selector must be 0x and 8 lowercase hex digits: $it" }
        }
        entry.primaryType?.let { requireText("$what primaryType", it) }
        require(entry.selector != null || entry.contract != null || entry.primaryType != null) {
            "$what names a selector, a contract or a primaryType"
        }
        require(entry.primaryType == null || entry.selector == null) { "$what names a selector or a primaryType, not both" }
        require(entry.condition == DenylistCondition.ANY || entry.selector != null) {
            "$what: only a selector can name an amount or an approval"
        }
        requireText("$what reason", entry.reason, MAX_MEANING)
    }

    private fun validateWallet(wallet: WalletDto) {
        val name = wallet.name
        requireText("wallet name", name)
        require(wallet.ecosystems.isNotEmpty()) { "wallet $name names no ecosystem" }
        wallet.ecosystems.forEach { requireText("wallet $name ecosystem", it) }
        require(wallet.formats.isNotEmpty()) { "wallet $name names no code format" }
        wallet.formats.forEach { requireText("wallet $name format", it) }
        requireText("wallet $name note", wallet.note, MAX_MEANING)
        requireText("wallet $name limitations", wallet.limitations, MAX_MEANING)
    }

    /** Non-blank, at most [max] characters, and no control or format characters (no spoofing marks). */
    private fun requireText(what: String, value: String, max: Int = MAX_TEXT) {
        require(
            value.isNotBlank() &&
                value.length <= max &&
                value.none { it.category == CharCategory.CONTROL || it.category == CharCategory.FORMAT },
        ) { "$what must be 1 to $max printable characters" }
    }

    private fun encode(payload: ByteArray, seed: ByteArray): ByteArray {
        val keyId = keyIdOf(publicKeyOf(seed))
        val message = DOMAIN + keyId + payload
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return ByteBuffer.allocate(HEADER_BYTES + payload.size + SIGNATURE_BYTES)
            .put(MAGIC)
            .putInt(payload.size)
            .put(keyId)
            .put(payload)
            .put(signer.generateSignature())
            .array()
    }

    private fun open(bytes: ByteArray, publicKey: ByteArray): ByteArray {
        require(bytes.size <= MAX_BYTES) { "package is larger than $MAX_BYTES bytes" }
        require(bytes.size > HEADER_BYTES + SIGNATURE_BYTES) { "package is truncated" }
        require(bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) {
            "not a version $FORMAT_VERSION registry package (magic ${String(MAGIC, StandardCharsets.US_ASCII)} expected)"
        }
        val declared = ByteBuffer.wrap(bytes, MAGIC.size, 4).int.toLong() and 0xffffffffL
        require(declared == (bytes.size - HEADER_BYTES - SIGNATURE_BYTES).toLong()) {
            "payload length does not match the file size"
        }
        val keyId = bytes.copyOfRange(KEY_ID_OFFSET, HEADER_BYTES)
        require(keyId.contentEquals(keyIdOf(publicKey))) { "package is signed with another key (key ID ${keyId.toHex()})" }
        val payload = bytes.copyOfRange(HEADER_BYTES, bytes.size - SIGNATURE_BYTES)
        val signature = bytes.copyOfRange(bytes.size - SIGNATURE_BYTES, bytes.size)
        val message = DOMAIN + keyId + payload
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        require(verifier.verifySignature(signature)) { "signature does not verify with this public key" }
        return payload
    }
}

/** The Function Selector of [signature]: the first four bytes of its Keccak-256 hash, as 0x and 8 lowercase hex digits. */
internal fun selectorOf(signature: String): String {
    val input = signature.toByteArray(StandardCharsets.US_ASCII)
    val digest = KeccakDigest(256)
    digest.update(input, 0, input.size)
    val hash = ByteArray(32)
    digest.doFinal(hash, 0)
    return "0x" + hash.copyOf(4).toHex()
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun String.hexToBytes(): ByteArray {
    val hex = trim()
    require(hex.length % 2 == 0 && hex.all { it.digitToIntOrNull(16) != null }) { "not hexadecimal" }
    return ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
