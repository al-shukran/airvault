package app.lensly.tools.registrypack

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** A registry package's content: the entries file and the signed payload share this shape. */
@Serializable
data class PackageDto(
    val formatVersion: Int,
    val sequence: Long,
    val createdAt: String,
    val networks: List<NetworkDto>,
    val tokens: List<TokenDto>,
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
 * File: `LRP1` | u32 big-endian payload length | payload | 64-byte Ed25519 signature.
 * Payload: canonical JSON (sorted keys, no whitespace, entries sorted by chain ID and then contract).
 * Signed message: `LENSLY-REGISTRY-v1\n` followed by the payload bytes.
 */
internal object Pack {
    const val FORMAT_VERSION = 1
    const val MAX_BYTES = 1 shl 20

    private val MAGIC = "LRP1".toByteArray(StandardCharsets.US_ASCII)
    private val DOMAIN = "LENSLY-REGISTRY-v1\n".toByteArray(StandardCharsets.US_ASCII)
    private const val HEADER_BYTES = 8
    private const val SIGNATURE_BYTES = 64
    private const val MAX_TEXT = 64
    private const val MAX_DECIMALS = 36
    private val CONTRACT = Regex("0x[0-9a-f]{40}")
    private val json = Json

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

    /** Canonical payload bytes for [content]. Entries are sorted here, so the order in the source never matters. */
    fun canonicalPayload(content: PackageDto): ByteArray = CanonicalJson.write(
        mapOf(
            "formatVersion" to content.formatVersion,
            "sequence" to content.sequence,
            "createdAt" to content.createdAt,
            "networks" to content.networks.sortedBy { it.chainId }.map(::networkMap),
            "tokens" to content.tokens.sortedWith(compareBy<TokenDto>({ it.chainId }, { it.contract })).map(::tokenMap),
        ),
    ).toByteArray(StandardCharsets.UTF_8)

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
    }

    /** Non-blank, at most [MAX_TEXT] characters, and no control or format characters (no spoofing marks). */
    private fun requireText(what: String, value: String) {
        require(
            value.isNotBlank() &&
                value.length <= MAX_TEXT &&
                value.none { it.category == CharCategory.CONTROL || it.category == CharCategory.FORMAT },
        ) { "$what must be 1 to $MAX_TEXT printable characters" }
    }

    private fun encode(payload: ByteArray, seed: ByteArray): ByteArray {
        val message = DOMAIN + payload
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return ByteBuffer.allocate(HEADER_BYTES + payload.size + SIGNATURE_BYTES)
            .put(MAGIC)
            .putInt(payload.size)
            .put(payload)
            .put(signer.generateSignature())
            .array()
    }

    private fun open(bytes: ByteArray, publicKey: ByteArray): ByteArray {
        require(bytes.size <= MAX_BYTES) { "package is larger than $MAX_BYTES bytes" }
        require(bytes.size > HEADER_BYTES + SIGNATURE_BYTES) { "package is truncated" }
        require(bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) { "not a Lensly registry package (bad magic)" }
        val declared = ByteBuffer.wrap(bytes, MAGIC.size, 4).int.toLong() and 0xffffffffL
        require(declared == (bytes.size - HEADER_BYTES - SIGNATURE_BYTES).toLong()) {
            "payload length does not match the file size"
        }
        val payload = bytes.copyOfRange(HEADER_BYTES, bytes.size - SIGNATURE_BYTES)
        val signature = bytes.copyOfRange(bytes.size - SIGNATURE_BYTES, bytes.size)
        val message = DOMAIN + payload
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        require(verifier.verifySignature(signature)) { "signature does not verify with this public key" }
        return payload
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun String.hexToBytes(): ByteArray {
    val hex = trim()
    require(hex.length % 2 == 0 && hex.all { it.digitToIntOrNull(16) != null }) { "not hexadecimal" }
    return ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
