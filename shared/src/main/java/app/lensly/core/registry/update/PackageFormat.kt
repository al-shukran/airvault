package app.lensly.core.registry.update

import app.lensly.core.registry.DenylistCondition
import app.lensly.core.registry.DenylistEntry
import app.lensly.core.registry.DenylistSeverity
import app.lensly.core.registry.FeeModel
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.ProxyBinding
import app.lensly.core.registry.TokenEntry
import app.lensly.core.registry.WalletEntry
import app.lensly.core.registry.WhitelistEntry
import app.lensly.core.registry.WhitelistParam
import app.lensly.core.registry.isDisplayText
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
 * File: `LRP2` | u32 big-endian payload length | 8-byte key ID | payload | 64-byte Ed25519 signature.
 * Key ID: the first 8 bytes of the SHA-256 of the signing public key. It selects the [TrustedKey] to verify with.
 * Payload: canonical JSON holding `formatVersion`, `minSchemaVersion` and the fields of [RegistryPackage].
 * Signed message: `LENSLY-REGISTRY-v2\n`, the key ID, then the payload bytes.
 *
 * Any other magic is refused as [RegistryUpdateException.UnsupportedFormat] before anything else is checked: `LRP1` has no key
 * ID, and `LRP3` or later is not read yet.
 *
 * Size, magic, length, key and signature are checked before any JSON is parsed.
 */
internal object PackageFormat {
    const val FORMAT_VERSION = 2L

    /**
     * The newest catalog schema this app interprets. A package states the schema it needs in `minSchemaVersion`, and one that
     * needs a newer schema is refused as [RegistryUpdateException.Incompatible] before its entries are read. Schema 2 adds the
     * denylist and the wallets, so an older app refuses a package that carries them instead of failing on unknown fields.
     */
    const val SCHEMA_VERSION = 2L
    const val MAX_BYTES = 1 shl 20

    private val MAGIC = "LRP2".toByteArray(StandardCharsets.US_ASCII)
    private val MAGIC_PREFIX = "LRP".toByteArray(StandardCharsets.US_ASCII)
    private val DOMAIN = "LENSLY-REGISTRY-v2\n".toByteArray(StandardCharsets.US_ASCII)
    private const val LENGTH_OFFSET = 4
    private const val KEY_ID_OFFSET = 8
    private const val HEADER_BYTES = KEY_ID_OFFSET + TrustedKey.ID_BYTES
    private const val FORMAT1_HEADER_BYTES = 8
    private const val SIGNATURE_BYTES = 64
    private const val MAX_TEXT = 64
    private const val MAX_MEANING = 240
    private const val MAX_DECIMALS = 36
    private val CONTRACT = Regex("0x[0-9a-f]{40}")
    private val json = Json

    /** The verified content of [bytes]: structure, signing key, signature, format, compatibility and entry rules, in that order. */
    fun read(bytes: ByteArray, trustedKeys: List<TrustedKey>): RegistryPackage = decode(open(bytes, trustedKeys))

    /**
     * The sequence a format 1 package states, without any verification, or null when [bytes] are not one. Only for a file
     * this app stored itself before format 2: it keeps the rollback check from starting over at 0 after the upgrade.
     */
    fun format1Sequence(bytes: ByteArray): Long? {
        if (otherMagicVersion(bytes) != 1L || bytes.size <= FORMAT1_HEADER_BYTES + SIGNATURE_BYTES) return null
        val payload = String(bytes, FORMAT1_HEADER_BYTES, bytes.size - FORMAT1_HEADER_BYTES - SIGNATURE_BYTES, StandardCharsets.UTF_8)
        return runCatching { json.parseToJsonElement(payload).jsonObject["sequence"]?.jsonPrimitive?.longOrNull }.getOrNull()
    }

    /** The version named by a registry magic other than this app's (`LRP1`, `LRP3`, ...), or null for any other file. */
    private fun otherMagicVersion(bytes: ByteArray): Long? {
        if (bytes.size < MAGIC.size || !bytes.copyOf(MAGIC_PREFIX.size).contentEquals(MAGIC_PREFIX)) return null
        if (bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) return null
        val digit = bytes[MAGIC_PREFIX.size].toInt().toChar()
        return if (digit in '0'..'9') (digit - '0').toLong() else null
    }

    /** The payload inside [bytes], once the size, magic, length, key, key status and signature checks pass. */
    private fun open(bytes: ByteArray, trustedKeys: List<TrustedKey>): ByteArray {
        if (bytes.size > MAX_BYTES) throw RegistryUpdateException.Malformed("larger than $MAX_BYTES bytes")
        otherMagicVersion(bytes)?.let { throw RegistryUpdateException.UnsupportedFormat(it, FORMAT_VERSION) }
        if (bytes.size <= HEADER_BYTES + SIGNATURE_BYTES) throw RegistryUpdateException.Malformed("truncated")
        if (!bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) {
            throw RegistryUpdateException.Malformed("not a Lensly registry package")
        }
        val declared = ByteBuffer.wrap(bytes, LENGTH_OFFSET, 4).int.toLong() and 0xffffffffL
        if (declared != (bytes.size - HEADER_BYTES - SIGNATURE_BYTES).toLong()) {
            throw RegistryUpdateException.Malformed("payload length does not match the file size")
        }
        val keyId = bytes.copyOfRange(KEY_ID_OFFSET, HEADER_BYTES)
        val payload = bytes.copyOfRange(HEADER_BYTES, bytes.size - SIGNATURE_BYTES)
        val signature = bytes.copyOfRange(bytes.size - SIGNATURE_BYTES, bytes.size)
        val idText = TrustedKey.hex(keyId)
        val key = trustedKeys.firstOrNull { it.id == idText } ?: throw RegistryUpdateException.UnknownKey(idText)
        if (key.status == KeyStatus.REVOKED) throw RegistryUpdateException.RevokedKey(idText)
        if (!verifies(key.publicKey, keyId + payload, signature)) throw RegistryUpdateException.BadSignature()
        return payload
    }

    /**
     * Parses and validates a signed payload's content. Checks the payload's own rules only. The caller checks
     * the baked catalog.
     */
    private fun decode(payload: ByteArray): RegistryPackage {
        val text = try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString()
        } catch (e: CharacterCodingException) {
            throw RegistryUpdateException.Malformed("payload is not UTF-8")
        }
        // The two version fields decide whether the rest can be read at all, so they are read first and must be JSON numbers,
        // not quoted ones. A package for a newer schema may hold fields this app does not know, which the strict decode below
        // would report as malformed.
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            throw RegistryUpdateException.Malformed("payload is not a JSON object")
        }
        fun integer(name: String): Long = root[name]?.jsonPrimitive?.takeUnless { it.isString }?.longOrNull
            ?: throw RegistryUpdateException.Malformed("payload has no integer $name")
        val version = integer("formatVersion")
        if (version != FORMAT_VERSION) throw RegistryUpdateException.UnsupportedFormat(version, FORMAT_VERSION)
        val minSchema = integer("minSchemaVersion")
        if (minSchema < 1) throw RegistryUpdateException.Malformed("minSchemaVersion must be at least 1")
        if (minSchema > SCHEMA_VERSION) throw RegistryUpdateException.Incompatible(minSchema, SCHEMA_VERSION)
        val dto = try {
            json.decodeFromString<Payload>(text)
        } catch (e: IllegalArgumentException) {
            // SerializationException is a subclass: unknown or missing fields, wrong types, unknown fee model.
            throw RegistryUpdateException.Malformed("payload does not match the schema: ${e.message}")
        }
        return validated(dto)
    }

    private fun verifies(publicKey: ByteArray, signed: ByteArray, signature: ByteArray): Boolean {
        val message = DOMAIN + signed
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
            network.icon?.let { requirePrintable("chain ${network.chainId} icon", it) }
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
            token.icon?.let { requirePrintable("token ${token.contract} icon", it) }
        }
        val approvals = dto.whitelist.map { Triple(it.chainId, it.contract, it.selector) }
        if (approvals.toSet().size != approvals.size) {
            throw RegistryUpdateException.Conflict("a whitelist entry appears more than once in the package")
        }
        for (entry in dto.whitelist) {
            requirePrintable("whitelist ${entry.signature} meaning", entry.meaning, MAX_MEANING)
        }
        val denylist = dto.denylist?.mapIndexed { index, record ->
            try {
                DenylistEntry(
                    chainId = record.chainId,
                    contract = record.contract,
                    selector = record.selector,
                    primaryType = record.primaryType,
                    condition = record.condition,
                    severity = record.severity,
                    reason = record.reason,
                )
            } catch (e: IllegalArgumentException) {
                throw RegistryUpdateException.InvalidEntry("denylist entry ${index + 1}: ${e.message}")
            }
        }
        val wallets = dto.wallets?.mapIndexed { index, record ->
            try {
                WalletEntry(record.name, record.ecosystems, record.formats, record.note, record.limitations)
            } catch (e: IllegalArgumentException) {
                throw RegistryUpdateException.InvalidEntry("wallet entry ${index + 1}: ${e.message}")
            }
        }
        if (wallets != null && wallets.map { it.name }.toSet().size != wallets.size) {
            throw RegistryUpdateException.Conflict("a wallet appears more than once in the package")
        }
        val networks = dto.networks.map {
            NetworkEntry(
                it.chainId, it.name, it.nativeSymbol, it.nativeDecimals, it.feeModel, it.explorerName, it.testnet,
                icon = it.icon,
            )
        }
        val tokens = dto.tokens.map {
            TokenEntry(it.chainId, it.contract, it.symbol, it.name, it.decimals, icon = it.icon)
        }
        // A package's whitelist replaces the build's, so its own entries are only checked against each other.
        val whitelist = dto.whitelist.map { record ->
            try {
                WhitelistEntry(
                    chainId = record.chainId,
                    contract = record.contract,
                    selector = record.selector,
                    signature = record.signature,
                    meaning = record.meaning,
                    proxy = record.proxy?.let { ProxyBinding(it.implementationAddress, it.implementationCodeHash) },
                    params = record.params.map { WhitelistParam(it.name, it.type, it.tokenRef) },
                    upgradeable = record.upgradeable,
                )
            } catch (e: IllegalArgumentException) {
                throw RegistryUpdateException.InvalidEntry("whitelist ${record.signature}: ${e.message}")
            }
        }
        return RegistryPackage(
            sequence = dto.sequence,
            createdAt = dto.createdAt,
            networks = networks.sortedBy { it.chainId },
            tokens = tokens.sortedWith(compareBy<TokenEntry>({ it.chainId }, { it.contract })),
            whitelist = whitelist,
            denylist = denylist,
            wallets = wallets,
        )
    }

    /**
     * Non-blank, at most [max] characters, with no control or format characters (no spoofing marks). Names are at most
     * [MAX_TEXT] characters; a whitelist meaning may run to [MAX_MEANING].
     */
    private fun requirePrintable(what: String, value: String, max: Int = MAX_TEXT) {
        if (!isDisplayText(value, max)) {
            throw RegistryUpdateException.InvalidEntry("$what must be 1 to $max printable characters")
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
        /** Optional. Packages signed before logos existed omit it. */
        val icon: String? = null,
    )

    @Serializable
    private class TokenRecord(
        val chainId: Long,
        val contract: String,
        val symbol: String,
        val name: String,
        val decimals: Int,
        /** Optional. Packages signed before logos existed omit it. */
        val icon: String? = null,
    )

    @Serializable
    private class WhitelistRecord(
        val chainId: Long,
        val contract: String,
        val selector: String,
        val signature: String,
        val meaning: String,
        val proxy: ProxyRecord? = null,
        val params: List<ParamRecord>,
        val upgradeable: Boolean,
    )

    @Serializable
    private class ParamRecord(
        val name: String,
        val type: String,
        val tokenRef: Boolean = false,
    )

    @Serializable
    private class ProxyRecord(
        val implementationAddress: String? = null,
        val implementationCodeHash: String? = null,
    )

    @Serializable
    private class DenylistRecord(
        val chainId: Long? = null,
        val contract: String? = null,
        val selector: String? = null,
        val primaryType: String? = null,
        val condition: DenylistCondition = DenylistCondition.ANY,
        val severity: DenylistSeverity,
        val reason: String,
    )

    @Serializable
    private class WalletRecord(
        val name: String,
        val ecosystems: List<String>,
        val formats: List<String>,
        val note: String,
        val limitations: String,
    )

    @Serializable
    private class Payload(
        val formatVersion: Long,
        val minSchemaVersion: Long,
        val sequence: Long,
        val createdAt: String,
        val networks: List<NetworkRecord>,
        val tokens: List<TokenRecord>,
        /** Optional. Packages signed before the whitelist existed omit it, and their whitelist is empty. */
        val whitelist: List<WhitelistRecord> = emptyList(),
        /** Optional. Omitted, the build's denylist stays in force. An empty list clears it. */
        val denylist: List<DenylistRecord>? = null,
        /** Optional. Omitted, the build's wallets stay in force. An empty list clears them. */
        val wallets: List<WalletRecord>? = null,
    )
}
