package app.lumio.data.tx

import app.lensly.core.vault.Aead
import java.security.GeneralSecurityException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A comment export that cannot be read: it is damaged, it belongs to another vault, or an entry is not one the format allows. */
class CommentImportException(message: String) : Exception(message)

/**
 * The export of the transaction comments: JSON sealed under the vault's export key, as Lumio seals its other persisted data. Each comment is written with
 * the transaction id it belongs to, which is what lets it land on the same transaction when it is restored. [restore] applies a comment unless the store
 * already holds one for the same transaction that is at least as new. So restoring the same export twice changes nothing.
 */
object TxCommentTransfer {
    private const val FORMAT = "lumio-tx-comments"
    private const val VERSION = 1
    private const val MAX_TEXT_CHARS = 2_000
    private const val MAX_ID_CHARS = 300
    private val AAD = "lumio-tx-comments-export-v1".toByteArray(Charsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = true }

    /** Seals every comment in [store] under the export key of [keys]. */
    suspend fun export(store: TxStore, keys: TxKeys): ByteArray {
        val file = ExportFile(FORMAT, VERSION, store.comments().map { ExportEntry(it.txId, it.text, it.updatedAt) })
        return Aead.seal(keys.exportKey, AAD, json.encodeToString(ExportFile.serializer(), file).encodeToByteArray())
    }

    /**
     * Opens [sealed] under the export key of [keys] and applies each comment that is newer than the one the store holds. Returns the number applied.
     * @throws CommentImportException when the export does not open, does not decode, or has an entry that the format does not allow.
     */
    suspend fun restore(store: TxStore, keys: TxKeys, sealed: ByteArray): Int {
        var applied = 0
        for (entry in open(keys, sealed).comments) {
            val held = store.comment(entry.txId)?.updatedAt
            if (held == null || held < entry.updatedAt) {
                store.setComment(entry.txId, entry.text, entry.updatedAt)
                applied++
            }
        }
        return applied
    }

    private fun open(keys: TxKeys, sealed: ByteArray): ExportFile {
        val plain = try {
            Aead.open(keys.exportKey, AAD, sealed)
        } catch (e: GeneralSecurityException) {
            throw CommentImportException("The export does not open under this vault.")
        } catch (e: IllegalArgumentException) {
            throw CommentImportException("The export is damaged.")
        }
        val file = try {
            json.decodeFromString(ExportFile.serializer(), plain.decodeToString())
        } catch (e: SerializationException) {
            throw CommentImportException("The export is not in the comment format.")
        } catch (e: IllegalArgumentException) {
            throw CommentImportException("The export is not in the comment format.")
        }
        if (file.format != FORMAT || file.version != VERSION) throw CommentImportException("The export is not a comment export of this version.")
        for (entry in file.comments) {
            val idOk = (entry.txId.startsWith("EVM:") || entry.txId.startsWith("BTC:")) && entry.txId.length <= MAX_ID_CHARS
            val textOk = entry.text.isNotBlank() && entry.text.length <= MAX_TEXT_CHARS
            if (!idOk || !textOk || entry.updatedAt < 0) throw CommentImportException("The export has a comment that the format does not allow.")
        }
        return file
    }
}

@Serializable
private class ExportFile(val format: String, val version: Int, val comments: List<ExportEntry>)

@Serializable
private class ExportEntry(val txId: String, val text: String, val updatedAt: Long)
