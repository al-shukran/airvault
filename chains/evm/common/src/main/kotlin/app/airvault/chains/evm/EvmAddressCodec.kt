package app.airvault.chains.evm

import app.airvault.core.model.AddressCodec
import app.airvault.core.model.AddressError
import app.airvault.core.model.AddressKind
import app.airvault.core.model.AddressResult
import app.airvault.core.model.ChainId
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.KeccakDigest

private const val EVM_NAMESPACE = "eip155"
private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

/** The Ethereum address rules (spec 3.1): the EIP-55 checksum, which is the same on every EVM network. */
object EvmAddressCodec : AddressCodec {
    override fun parse(chain: ChainId, text: String): AddressResult {
        if (chain.namespace != EVM_NAMESPACE) return AddressResult.Invalid(AddressError.WRONG_NETWORK)
        evmAddressOf(text)?.let { return AddressResult.Valid(it, AddressKind.DEFAULT) }
        val reason = when {
            text.length != EVM_ADDRESS_LENGTH -> AddressError.LENGTH
            !EVM_ADDRESS.matches(text) -> AddressError.CHARSET
            else -> AddressError.CHECKSUM
        }
        return AddressResult.Invalid(reason)
    }

    /**
     * The address of a secp256k1 public key, uncompressed (65 bytes) or compressed (33 bytes): the last 20 bytes of the Keccak-256 of its X and
     * Y coordinates. A compressed key that is not on the curve throws IllegalArgumentException.
     */
    override fun fromPublicKey(chain: ChainId, publicKey: ByteArray, kind: AddressKind): String {
        val xy = when {
            publicKey.size == UNCOMPRESSED_KEY_BYTES && publicKey[0] == UNCOMPRESSED_PREFIX -> publicKey.copyOfRange(1, publicKey.size)
            publicKey.size == XY_BYTES -> publicKey
            publicKey.size == COMPRESSED_KEY_BYTES -> SECP256K1.curve.decodePoint(publicKey).normalize().let { point ->
                point.affineXCoord.encoded + point.affineYCoord.encoded
            }
            else -> throw IllegalArgumentException("An Ethereum address needs a public key of 33 bytes (compressed) or 65 bytes (uncompressed)")
        }
        val hash = keccak256(xy)
        return toChecksumAddress("0x" + hash.copyOfRange(hash.size - ADDRESS_BYTES, hash.size).toHex())
    }
}

private const val EVM_ADDRESS_LENGTH = 42
private const val ADDRESS_BYTES = 20
private const val XY_BYTES = 64
private const val COMPRESSED_KEY_BYTES = 33
private val SECP256K1 = SECNamedCurves.getByName("secp256k1")
private const val UNCOMPRESSED_KEY_BYTES = 65
private const val UNCOMPRESSED_PREFIX: Byte = 0x04

/**
 * The EVM address [text] in its checksum form, or null when it is not one. An address in one letter case is taken as it is. A mixed-case
 * address must carry the right checksum.
 */
fun evmAddressOf(text: String): String? {
    if (!EVM_ADDRESS.matches(text)) return null
    val checksum = toChecksumAddress(text)
    val hex = text.substring(2)
    val mixed = hex != hex.lowercase() && hex != hex.uppercase()
    return if (mixed && checksum != text) null else checksum
}

/** EIP-55: each letter of the lower-case hex is upper case when the matching nibble of the Keccak-256 of that hex is 8 or more. */
private fun toChecksumAddress(address: String): String {
    val lower = address.substring(2).lowercase()
    val hash = keccak256(lower.toByteArray(Charsets.US_ASCII))
    val out = StringBuilder("0x")
    for ((i, c) in lower.withIndex()) {
        val nibble = (hash[i / 2].toInt() shr (if (i % 2 == 0) 4 else 0)) and 0x0f
        out.append(if (nibble >= 8) c.uppercaseChar() else c)
    }
    return out.toString()
}

private fun keccak256(input: ByteArray): ByteArray {
    val digest = KeccakDigest(256)
    digest.update(input, 0, input.size)
    val out = ByteArray(digest.digestSize)
    digest.doFinal(out, 0)
    return out
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
