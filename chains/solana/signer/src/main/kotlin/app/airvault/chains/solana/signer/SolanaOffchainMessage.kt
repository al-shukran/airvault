package app.airvault.chains.solana.signer

import app.airvault.core.model.isPrintableCodePoint
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * A Solana off-chain message as Lensly reviews it before it signs: the envelope a key holder signs to approve a text, such as a sign-in.
 * Read-only. [parse] checks every length and value before it reads one, and nothing here signs.
 *
 * The layout is Solana's off-chain message format at header version 0, as the Solana proposal for off-chain message signing gives it.
 * In order: the signing domain (16 bytes, `\xffsolana offchain`), the header version (1 byte, 0), the application domain (32 bytes), the
 * message format (1 byte), the signer count (1 byte, at least 1), the signers (32 bytes each), the message length (2 bytes, little-endian,
 * at least 1) and the message. The whole envelope is at most [SolanaMessage.MAX_BYTES] bytes. The signature covers every byte of it, and
 * the leading 0xff keeps the envelope from ever being read as a transaction.
 */
class SolanaOffchainMessage private constructor(
    /** The application domain, 32 bytes that name what the message is for. The review shows it as an address. */
    val applicationDomain: ByteArray,
    /** How the message is encoded. Lensly signs only [Format.RESTRICTED_ASCII] and [Format.LIMITED_UTF8]. */
    val format: Format,
    /** The public keys the message names as signers, 32 bytes each, in the order the message gives them. */
    val signers: List<ByteArray>,
    /** The message as text. Every character in it can be read by eye, so the review shows all of it. */
    val text: String,
) {
    /** The formats of the off-chain standard, by their wire value. Extended UTF-8 is not one Lensly signs. */
    enum class Format(val wire: Int) {
        RESTRICTED_ASCII(0),
        LIMITED_UTF8(1),
    }

    /** Outcome of [parse]. Parsing never throws, so every failure comes back as [Rejected]. */
    sealed interface Result {
        class Ok(val message: SolanaOffchainMessage) : Result

        class Rejected(val reason: String) : Result
    }

    companion object {
        /** The 16 bytes every off-chain message begins with: 0xff, then "solana offchain". */
        private val SIGNING_DOMAIN: ByteArray = byteArrayOf(0xff.toByte()) + "solana offchain".toByteArray(StandardCharsets.US_ASCII)

        private const val HEADER_VERSION = 0
        private const val APPLICATION_DOMAIN_BYTES = 32
        private const val SIGNER_BYTES = 32
        private const val LENGTH_BYTES = 2

        /** The reason for bytes that do not begin with the signing domain. */
        const val NOT_OFF_CHAIN = "Lensly signs a message only in the Solana off-chain message format, and these bytes are not in it."

        /** The reason for bytes that end before the fields they name. */
        const val TRUNCATED = "The off-chain message ends before its fields do."

        /** The reason for a header version other than 0. */
        const val UNSUPPORTED_VERSION = "The off-chain message has a header version that Lensly does not sign."

        /** The reason for a message format other than restricted ASCII or limited UTF-8. */
        const val UNSUPPORTED_FORMAT = "The off-chain message is in a format that Lensly does not sign."

        /** The reason for a message that names no signer. */
        const val NO_SIGNER = "The off-chain message names no signer."

        /** The reason for a message with no text. */
        const val EMPTY_MESSAGE = "The off-chain message is empty."

        /** The reason for a length field that does not match the bytes after it. */
        const val LENGTH_MISMATCH = "The off-chain message length does not match its message."

        /** The reason for an envelope longer than a Solana packet. */
        const val TOO_LONG = "The off-chain message is longer than a Solana packet."

        /** The reason for restricted ASCII that holds a byte outside printable ASCII, such as a line break. */
        const val NOT_ASCII = "The message names restricted ASCII, but it holds a byte outside printable ASCII."

        /** The reason for limited UTF-8 whose bytes are not valid UTF-8. */
        const val NOT_UTF8 = "The message names UTF-8, but its bytes are not valid UTF-8."

        /** The reason for text that holds a character the owner could not read, such as a control or an invisible character. */
        const val UNSHOWABLE = "The message holds a character that Lensly cannot show as text."

        /** Parses [bytes] as an off-chain message. Any input that is not a well-formed one is [Result.Rejected]. */
        fun parse(bytes: ByteArray): Result {
            val reader = OffchainReader(bytes)
            val domain = reader.take(SIGNING_DOMAIN.size)
            if (domain == null || !domain.contentEquals(SIGNING_DOMAIN)) return Result.Rejected(NOT_OFF_CHAIN)
            val version = reader.byte() ?: return Result.Rejected(TRUNCATED)
            if (version != HEADER_VERSION) return Result.Rejected(UNSUPPORTED_VERSION)
            val applicationDomain = reader.take(APPLICATION_DOMAIN_BYTES) ?: return Result.Rejected(TRUNCATED)
            val format = when (reader.byte() ?: return Result.Rejected(TRUNCATED)) {
                Format.RESTRICTED_ASCII.wire -> Format.RESTRICTED_ASCII
                Format.LIMITED_UTF8.wire -> Format.LIMITED_UTF8
                else -> return Result.Rejected(UNSUPPORTED_FORMAT)
            }
            val signerCount = reader.byte() ?: return Result.Rejected(TRUNCATED)
            if (signerCount == 0) return Result.Rejected(NO_SIGNER)
            val signers = ArrayList<ByteArray>(signerCount)
            repeat(signerCount) { signers.add(reader.take(SIGNER_BYTES) ?: return Result.Rejected(TRUNCATED)) }
            val lengthBytes = reader.take(LENGTH_BYTES) ?: return Result.Rejected(TRUNCATED)
            val length = (lengthBytes[0].toInt() and 0xff) or ((lengthBytes[1].toInt() and 0xff) shl 8)
            if (length == 0) return Result.Rejected(EMPTY_MESSAGE)
            if (length != reader.remaining) return Result.Rejected(LENGTH_MISMATCH)
            if (bytes.size > SolanaMessage.MAX_BYTES) return Result.Rejected(TOO_LONG)
            val body = reader.take(length) ?: return Result.Rejected(LENGTH_MISMATCH)
            val text = when (format) {
                Format.RESTRICTED_ASCII -> {
                    if (body.any { it < 0x20 || it > 0x7e }) return Result.Rejected(NOT_ASCII)
                    String(body, StandardCharsets.US_ASCII)
                }
                Format.LIMITED_UTF8 -> {
                    val decoded = decodeUtf8(body) ?: return Result.Rejected(NOT_UTF8)
                    if (!decoded.codePoints().allMatch(::isPrintableCodePoint)) return Result.Rejected(UNSHOWABLE)
                    decoded
                }
            }
            return Result.Ok(SolanaOffchainMessage(applicationDomain, format, signers, text))
        }

        /** The text of [bytes] when they are valid UTF-8, and null for any other bytes. */
        private fun decodeUtf8(bytes: ByteArray): String? = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}

/** The bytes of an off-chain message, read from the front one field at a time. A read that runs past the end returns null. */
private class OffchainReader(private val bytes: ByteArray) {
    private var position = 0

    /** The bytes left to read. */
    val remaining: Int get() = bytes.size - position

    /** The next [count] bytes, or null when fewer are left. */
    fun take(count: Int): ByteArray? {
        if (count < 0 || count > remaining) return null
        val taken = bytes.copyOfRange(position, position + count)
        position += count
        return taken
    }

    /** The next byte as an unsigned value, or null at the end. */
    fun byte(): Int? = take(1)?.let { it[0].toInt() and 0xff }
}
