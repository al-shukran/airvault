package app.lensly.core.registry.update

import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.TokenEntry
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Wire format of a registry package. The writer is `:tools:registry-pack`; the two must agree.
 *
 * File: `LRP1` | u32 big-endian payload length | payload | 64-byte Ed25519 signature.
 * Payload: canonical JSON holding `formatVersion` and the fields of [RegistryPackage].
 * Signed message: `LENSLY-REGISTRY-v1\n` followed by the payload bytes.
 *
 * Size, magic, length and signature are checked before any JSON is parsed.
 */
internal object PackageFormat {
    const val FORMAT_VERSION = 1L
    const val MAX_BYTES = 1 shl 20

    private val MAGIC = "LRP1".toByteArray(StandardCharsets.US_ASCII)
    private val DOMAIN = "LENSLY-REGISTRY-v1\n".toByteArray(StandardCharsets.US_ASCII)
    private const val HEADER_BYTES = 8
    private const val SIGNATURE_BYTES = 64
    private const val MAX_TEXT = 64
    private const val MAX_DECIMALS = 36
    private val CONTRACT = Regex("0x[0-9a-f]{40}")
    private val json = Json

    /** The payload inside [bytes], once the size, magic, length and signature checks pass. */
    fun open(bytes: ByteArray, trustedPublicKeys: List<ByteArray>): ByteArray {
        if (bytes.size > MAX_BYTES) throw RegistryUpdateException.Malformed("larger than $MAX_BYTES bytes")
        if (bytes.size <= HEADER_BYTES + SIGNATURE_BYTES) throw RegistryUpdateException.Malformed("truncated")
        if (!bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) {
            throw RegistryUpdateException.Malformed("not a Lensly registry package")
        }
        val declared = ByteBuffer.wrap(bytes, MAGIC.size, 4).int.toLong() and 0xffffffffL
        if (declared != (bytes.size - HEADER_BYTES - SIGNATURE_BYTES).toLong()) {
            throw RegistryUpdateException.Malformed("payload length does not match the file size")
        }
        val payload = bytes.copyOfRange(HEADER_BYTES, bytes.size - SIGNATURE_BYTES)
        val signature = bytes.copyOfRange(bytes.size - SIGNATURE_BYTES, bytes.size)
        if (trustedPublicKeys.none { verifies(it, payload, signature) }) {
            throw RegistryUpdateException.BadSignature()
        }
        return payload
    }

    /**
     * Parses and validates a signed payload's content. Checks the payload's own rules only. The caller checks
     * the baked catalog.
     */
    fun decode(payload: ByteArray): RegistryPackage {
        val text = try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString()
        } catch (e: CharacterCodingException) {
            throw RegistryUpdateException.Malformed("payload is not UTF-8")
        }
        // formatVersion picks the reader, so it must be a JSON number, not a quoted one.
        val version = try {
            json.parseToJsonElement(text).jsonObject["formatVersion"]?.jsonPrimitive
                ?.takeUnless { it.isString }?.longOrNull
        } catch (e: IllegalArgumentException) {
            throw RegistryUpdateException.Malformed("payload is not a JSON object")
        }
        if (version == null) throw RegistryUpdateException.Malformed("payload has no integer formatVersion")
        if (version != FORMAT_VERSION) throw RegistryUpdateException.UnsupportedFormat(version)
        val dto = try {
            json.decodeFromString<Payload>(text)
        } catch (e: IllegalArgumentException) {
            // SerializationException is a subclass: unknown or missing fields, wrong types, unknown fee model.
            throw RegistryUpdateException.Malformed("payload does not match the schema: ${e.message}")
        }
        return validated(dto)
    }

    /** Parses a public key given as 64 hexadecimal characters, as stored in the `registry/release.pub` asset. */
    fun parsePublicKey(text: String): ByteArray {
        val hex = text.trim()
        require(hex.length == 64 && hex.all { it.digitToIntOrNull(16) != null }) {
            "public key must be 64 hexadecimal characters"
        }
        return ByteArray(32) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    private fun verifies(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32) return false
        val message = DOMAIN + payload
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }

    /**
     * Checks every rule before any entry object is built, so each value failure is an
     * [RegistryUpdateException.InvalidEntry].
     */
    private fun validated(dto: Payload): RegistryPackage {
        if (dto.sequence < 1) throw RegistryUpdateException.Malformed("sequence must be at least 1")
        try {
            LocalDate.parse(dto.createdAt)
        } catch (e: DateTimeParseException) {
            throw RegistryUpdateException.Malformed("createdAt is not an ISO date")
        }
        if (dto.networks.map { it.chainId }.toSet().size != dto.networks.size) {
            throw RegistryUpdateException.Conflict("a network chain ID appears more than once in the package")
        }
        if (dto.tokens.map { it.chainId to it.contract }.toSet().size != dto.tokens.size) {
            throw RegistryUpdateException.Conflict("a token appears more than once in the package")
        }
        for (network in dto.networks) {
            if (network.chainId <= 0) {
                throw RegistryUpdateException.InvalidEntry("chainId must be positive: ${network.chainId}")
            }
            // RegistryLoader guarantees that release builds never see a testnet, so a package cannot add one.
            if (network.testnet) {
                throw RegistryUpdateException.InvalidEntry("chain ${network.chainId}: a package cannot add a testnet")
            }
            if (network.nativeDecimals !in 0..MAX_DECIMALS) {
                throw RegistryUpdateException.InvalidEntry(
                    "chain ${network.chainId}: nativeDecimals must be 0 to $MAX_DECIMALS",
                )
            }
            requirePrintable("chain ${network.chainId} name", network.name)
            requirePrintable("chain ${network.chainId} nativeSymbol", network.nativeSymbol)
            network.explorerName?.let { requirePrintable("chain ${network.chainId} explorerName", it) }
        }
        for (token in dto.tokens) {
            if (token.chainId <= 0) {
                throw RegistryUpdateException.InvalidEntry("chainId must be positive: ${token.chainId}")
            }
            if (!CONTRACT.matches(token.contract)) {
                throw RegistryUpdateException.InvalidEntry(
                    "contract must be 0x followed by 40 lowercase hex digits: ${token.contract}",
                )
            }
            if (token.decimals !in 0..MAX_DECIMALS) {
                throw RegistryUpdateException.InvalidEntry(
                    "token ${token.contract}: decimals must be 0 to $MAX_DECIMALS",
                )
            }
            requirePrintable("token ${token.contract} symbol", token.symbol)
            requirePrintable("token ${token.contract} name", token.name)
        }
        val networks = dto.networks.map {
            NetworkEntry(
                it.chainId, it.name, it.nativeSymbol, it.nativeDecimals, it.feeModel, it.explorerName, it.testnet,
            )
        }
        val tokens = dto.tokens.map { TokenEntry(it.chainId, it.contract, it.symbol, it.name, it.decimals) }
        return RegistryPackage(
            sequence = dto.sequence,
            createdAt = dto.createdAt,
            networks = networks.sortedBy { it.chainId },
            tokens = tokens.sortedWith(compareBy<TokenEntry>({ it.chainId }, { it.contract })),
        )
    }

    /** Non-blank, at most [MAX_TEXT] characters, with no control or format characters (no spoofing marks). */
    private fun requirePrintable(what: String, value: String) {
        val printable = value.isNotBlank() &&
            value.length <= MAX_TEXT &&
            value.none { it.category == CharCategory.CONTROL || it.category == CharCategory.FORMAT }
        if (!printable) {
            throw RegistryUpdateException.InvalidEntry("$what must be 1 to $MAX_TEXT printable characters")
        }
    }

    /** Entries as they appear in the payload. Entry objects are built only after [validated] has checked them. */
    @Serializable
    private class NetworkRecord(
        val chainId: Long,
        val name: String,
        val nativeSymbol: String,
        val nativeDecimals: Int,
        val feeModel: FeeModel,
        val explorerName: String?,
        val testnet: Boolean,
    )

    @Serializable
    private class TokenRecord(
        val chainId: Long,
        val contract: String,
        val symbol: String,
        val name: String,
        val decimals: Int,
    )

    @Serializable
    private class Payload(
        val formatVersion: Long,
        val sequence: Long,
        val createdAt: String,
        val networks: List<NetworkRecord>,
        val tokens: List<TokenRecord>,
    )
}
