package app.lumio.data.config

/**
 * A change that a store refused because the record it would replace does not parse. The record is left as it is, so the defaults that a read showed in its
 * place never overwrite what the owner saved. The message is the owner's words.
 */
class UnreadableRecordException(what: String, cause: Throwable) :
    IllegalStateException("Your saved $what could not be read, so nothing was changed.", cause)

/** Runs [decode] on a stored record, and turns a record that does not parse into an [UnreadableRecordException] that names [what] the record holds. */
internal inline fun <T> decodeOrRefuse(what: String, decode: () -> T): T = try {
    decode()
} catch (e: IllegalArgumentException) {
    // SerializationException is an IllegalArgumentException, and so is a value that a record class refuses.
    throw UnreadableRecordException(what, e)
}

/** The owner's words for a failed save: the refusal's own words when the stored record is unreadable, and [fallback] for any other failure. */
fun Throwable.saveFailureWords(fallback: String): String = if (this is UnreadableRecordException) message ?: fallback else fallback
