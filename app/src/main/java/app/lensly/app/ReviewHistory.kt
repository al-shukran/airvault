package app.lensly.app

import app.lensly.app.sign.ReviewKind
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ReviewRecord
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultSession
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/** The name prefix of a Review Record. The rest of its name is a random id, so the name shows neither the time nor the content. */
private const val REVIEW_PREFIX = "review-"

/**
 * The Review Records of the wallets that an open [VaultSession] holds. Each record is one file in its wallet's folder, sealed under
 * the wallet's Per-Wallet Key. So Delete Wallet and Wipe shred it with the wallet, and a decoy session reads only its own records.
 */
class ReviewHistory(private val session: VaultSession) {

    /** Stores [record] as a new Review Record of wallet [walletId]. */
    fun append(walletId: String, record: ReviewRecord) {
        val bytes = ReviewRecordCodec.encode(record)
        try {
            session.putRecord(walletId, REVIEW_PREFIX + UUID.randomUUID(), bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * The Review Records of wallet [walletId], newest first. A record that cannot be read is left out. A wallet this session cannot
     * open has none.
     */
    fun list(walletId: String): List<ReviewRecord> = session.recordNames(walletId, REVIEW_PREFIX)
        .mapNotNull { name -> read(walletId, name) }
        .sortedByDescending { it.atMs }

    private fun read(walletId: String, name: String): ReviewRecord? {
        val bytes = try {
            session.getRecord(walletId, name) ?: return null
        } catch (e: VaultException) {
            return null
        }
        return try {
            ReviewRecordCodec.decode(bytes)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } finally {
            bytes.fill(0)
        }
    }
}

/**
 * The binary layout of a Review Record, written the way [WalletRecords] writes its records: a version byte, then each field in
 * order. Text is a length and its UTF-8 bytes, so a long message fits, which DataOutputStream.writeUTF refuses past 64 KiB. Each
 * optional field is a flag, then its value when the flag is set.
 */
internal object ReviewRecordCodec {
    private const val VERSION = 1
    private const val MAX_TEXT_BYTES = 1 shl 20
    private const val MAX_ROWS = 256

    fun encode(record: ReviewRecord): ByteArray = WalletRecords.writeBytes { out ->
        out.writeByte(VERSION)
        out.writeByte(record.outcome.wire)
        out.writeByte(record.kind.wire)
        out.writeLong(record.atMs)
        out.writeOptional(record.chainId) { writeLong(it) }
        out.writeOptional(record.networkName) { writeText(it) }
        out.writeOptional(record.accountIndex) { writeInt(it) }
        out.writeOptional(record.address) { writeText(it) }
        out.writeOptional(record.function) { writeText(it) }
        out.writeOptional(record.to) { writeText(it) }
        out.writeOptional(record.amount) { writeText(it) }
        out.writeOptional(record.tokenContract) { writeText(it) }
        out.writeRows(record.fee)
        out.writeRows(record.params)
        out.writeOptional(record.origin) { writeText(it) }
        out.writeOptional(record.refusal) { writeText(it) }
        out.writeText(record.dataHash)
    }

    fun decode(bytes: ByteArray): ReviewRecord = WalletRecords.readBytes(bytes) { input ->
        val version = input.readUnsignedByte()
        require(version == VERSION) { "Unknown record version $version." }
        val outcome = ReviewOutcome.fromWire(input.readUnsignedByte())
        val kind = ReviewKind.fromWire(input.readUnsignedByte())
        val atMs = input.readLong()
        val chainId = input.readOptional { readLong() }
        val networkName = input.readOptional { readText() }
        val accountIndex = input.readOptional { readInt() }
        val address = input.readOptional { readText() }
        val function = input.readOptional { readText() }
        val to = input.readOptional { readText() }
        val amount = input.readOptional { readText() }
        val tokenContract = input.readOptional { readText() }
        val fee = input.readRows()
        val params = input.readRows()
        val origin = input.readOptional { readText() }
        val refusal = input.readOptional { readText() }
        val dataHash = input.readText()
        ReviewRecord(
            atMs = atMs,
            outcome = outcome,
            kind = kind,
            chainId = chainId,
            networkName = networkName,
            accountIndex = accountIndex,
            address = address,
            function = function,
            to = to,
            amount = amount,
            tokenContract = tokenContract,
            fee = fee,
            params = params,
            origin = origin,
            refusal = refusal,
            dataHash = dataHash,
        )
    }

    private inline fun <T : Any> DataOutputStream.writeOptional(value: T?, writeValue: DataOutputStream.(T) -> Unit) {
        writeBoolean(value != null)
        if (value != null) this.writeValue(value)
    }

    private inline fun <T> DataInputStream.readOptional(readValue: DataInputStream.() -> T): T? =
        if (readBoolean()) this.readValue() else null

    private fun DataOutputStream.writeText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        require(size in 0..MAX_TEXT_BYTES) { "Text is out of range." }
        return String(ByteArray(size).also { readFully(it) }, Charsets.UTF_8)
    }

    private fun DataOutputStream.writeRows(rows: List<Pair<String, String>>) {
        writeInt(rows.size)
        rows.forEach { (label, value) ->
            writeText(label)
            writeText(value)
        }
    }

    private fun DataInputStream.readRows(): List<Pair<String, String>> {
        val count = readInt()
        require(count in 0..MAX_ROWS) { "Too many rows." }
        return List(count) { readText() to readText() }
    }
}
