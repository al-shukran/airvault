package app.lensly.core.registry

import app.lensly.core.wallet.toHex
import java.math.BigInteger
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric

/** One decoded argument of a whitelisted call: the param it belongs to and its full value. */
data class DecodedParam(val param: WhitelistParam, val value: String)

/** The decoded params of a whitelisted call, or the reason its calldata is refused. */
sealed interface StaticDecode {
    data class Ok(val params: List<DecodedParam>) : StaticDecode
    data class Refused(val reason: String) : StaticDecode
}

/**
 * Decodes the calldata of a whitelisted call from the static types its entry declares. Every argument is one 32-byte word
 * after the four-byte selector, so a call whose length is anything else is refused. An address is shown as a full EIP-55
 * checksummed address, a uint or int as its exact decimal value in range for its width, a bool as true or false, and bytesN
 * as its full hex value. A value with high bits or padding that is not zero is refused, so nothing hidden is signed.
 */
object StaticAbiDecoder {
    private const val SELECTOR_BYTES = 4
    private const val WORD_BYTES = 32

    const val LENGTH_REFUSAL = "The calldata does not match the approved signature"
    const val RANGE_REFUSAL = "An argument is out of range for its type"
    const val BOOL_REFUSAL = "A bool argument is not 0 or 1"
    const val PADDING_REFUSAL = "An argument has padding that is not zero"

    fun decode(calldata: ByteArray, params: List<WhitelistParam>): StaticDecode {
        if (calldata.size != SELECTOR_BYTES + WORD_BYTES * params.size) return StaticDecode.Refused(LENGTH_REFUSAL)
        val decoded = ArrayList<DecodedParam>(params.size)
        for ((index, param) in params.withIndex()) {
            val start = SELECTOR_BYTES + index * WORD_BYTES
            val word = calldata.copyOfRange(start, start + WORD_BYTES)
            decoded += when {
                param.type == "address" -> decodeAddress(param, word) ?: return StaticDecode.Refused(PADDING_REFUSAL)
                param.type == "bool" -> decodeBool(param, word)
                    ?: return StaticDecode.Refused(BOOL_REFUSAL)
                param.type.startsWith("uint") -> decodeUint(param, word)
                    ?: return StaticDecode.Refused(RANGE_REFUSAL)
                param.type.startsWith("int") -> decodeInt(param, word)
                    ?: return StaticDecode.Refused(RANGE_REFUSAL)
                param.type.startsWith("bytes") -> decodeBytes(param, word)
                    ?: return StaticDecode.Refused(PADDING_REFUSAL)
                else -> return StaticDecode.Refused(RANGE_REFUSAL)
            }
        }
        return StaticDecode.Ok(decoded)
    }

    /**
     * The argument words that [decoded] encodes, written the way [decode] reads them, or null when a value is not written that
     * way. Compared with the calldata after its selector, the decoded values are exactly what is signed.
     */
    fun encode(decoded: List<DecodedParam>): ByteArray? {
        val words = decoded.map { wordOf(it.param, it.value) ?: return null }
        return words.fold(ByteArray(0)) { all, word -> all + word }
    }

    private val CANONICAL_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

    private fun wordOf(param: WhitelistParam, value: String): ByteArray? {
        val type = param.type
        return when {
            type == "address" -> if (CANONICAL_ADDRESS.matches(value) && Keys.toChecksumAddress(value) == value) {
                ByteArray(WORD_BYTES - 20) + Numeric.hexStringToByteArray(value)
            } else {
                null
            }
            type == "bool" -> when (value) {
                "true" -> uintWord(BigInteger.ONE)
                "false" -> uintWord(BigInteger.ZERO)
                else -> null
            }
            type.startsWith("uint") -> {
                val bits = type.removePrefix("uint").toInt()
                decimal(value)?.takeIf { it.signum() >= 0 && it.bitLength() <= bits }?.let { uintWord(it) }
            }
            type.startsWith("int") -> {
                val limit = BigInteger.ONE.shiftLeft(type.removePrefix("int").toInt() - 1)
                decimal(value)?.takeIf { it >= limit.negate() && it < limit }?.let { intWord(it) }
            }
            type.startsWith("bytes") -> {
                val size = type.removePrefix("bytes").toInt()
                if (Regex("0x[0-9a-f]{${2 * size}}").matches(value)) {
                    Numeric.hexStringToByteArray(value) + ByteArray(WORD_BYTES - size)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /** A decimal number written the way [BigInteger.toString] writes it, or null. */
    private fun decimal(value: String): BigInteger? = value.toBigIntegerOrNull()?.takeIf { it.toString() == value }

    private fun uintWord(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(WORD_BYTES - bytes.size) + bytes
    }

    /** A signed value as its two's complement word, sign-extended to 32 bytes. */
    private fun intWord(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        val fill: Byte = if (value.signum() < 0) -1 else 0
        return ByteArray(WORD_BYTES) { fill }.also { bytes.copyInto(it, WORD_BYTES - bytes.size) }
    }

    private fun decodeAddress(param: WhitelistParam, word: ByteArray): DecodedParam? {
        if (word.copyOfRange(0, 12).any { it != 0.toByte() }) return null
        val address = "0x" + word.copyOfRange(12, WORD_BYTES).toHex()
        return DecodedParam(param, Keys.toChecksumAddress(address))
    }

    private fun decodeBool(param: WhitelistParam, word: ByteArray): DecodedParam? {
        val value = BigInteger(1, word)
        if (value != BigInteger.ZERO && value != BigInteger.ONE) return null
        return DecodedParam(param, if (value == BigInteger.ONE) "true" else "false")
    }

    private fun decodeUint(param: WhitelistParam, word: ByteArray): DecodedParam? {
        val bits = param.type.removePrefix("uint").toInt()
        val value = BigInteger(1, word)
        if (value.bitLength() > bits) return null
        return DecodedParam(param, value.toString())
    }

    private fun decodeInt(param: WhitelistParam, word: ByteArray): DecodedParam? {
        val bits = param.type.removePrefix("int").toInt()
        val value = BigInteger(word)
        val min = BigInteger.ONE.shiftLeft(bits - 1).negate()
        val max = BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE)
        if (value < min || value > max) return null
        return DecodedParam(param, value.toString())
    }

    private fun decodeBytes(param: WhitelistParam, word: ByteArray): DecodedParam? {
        val size = param.type.removePrefix("bytes").toInt()
        if (word.copyOfRange(size, WORD_BYTES).any { it != 0.toByte() }) return null
        return DecodedParam(param, "0x" + word.copyOfRange(0, size).toHex())
    }
}
