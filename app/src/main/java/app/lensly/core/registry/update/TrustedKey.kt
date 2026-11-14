package app.lensly.core.registry.update

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Whether a build still accepts registry packages signed with a key. */
@Serializable
enum class KeyStatus {
    @SerialName("active") ACTIVE,
    @SerialName("revoked") REVOKED,
}

/**
 * An Ed25519 public key that a build may accept registry packages from. A [KeyStatus.REVOKED] key stays listed, so a package
 * it signed is refused by name rather than as an unknown key.
 */
class TrustedKey(val publicKey: ByteArray, val status: KeyStatus = KeyStatus.ACTIVE) {
    init {
        require(publicKey.size == 32) { "public key must be 32 bytes" }
    }

    /** The key ID a package header carries: the first [ID_BYTES] bytes of the SHA-256 of the key, as hexadecimal. */
    val id: String = hex(MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(ID_BYTES))

    companion object {
        const val ID_BYTES = 8

        internal fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

/**
 * The keys a build trusts. Each entry has an `id`, a `publicKey` (64 hexadecimal characters) and a `status`.
 *
 * Release keys are listed in [RELEASE_PATH]. The development key is listed in [DEBUG_PATH], which exists only in the debug source
 * set, so a release build can never accept a package signed with it.
 *
 * Rotation: list the new key as `active` and sign with it, then set the old key to `revoked`. Packages from a revoked key are
 * refused, including one already installed. The installed sequence stays recorded, so an older package still cannot be installed.
 */
object TrustedKeys {
    const val RELEASE_PATH = "registry/trusted-keys.json"
    const val DEBUG_PATH = "registry/trusted-keys.debug.json"

    /** [readAsset] returns the UTF-8 text at a published asset path, or null when the asset is absent. */
    fun load(readAsset: (String) -> String?): List<TrustedKey> {
        val release = parse(RELEASE_PATH, checkNotNull(readAsset(RELEASE_PATH)) { "missing asset $RELEASE_PATH" })
        val debug = readAsset(DEBUG_PATH)?.let { parse(DEBUG_PATH, it) }.orEmpty()
        return (release + debug).also { keys ->
            require(keys.map { it.id }.toSet().size == keys.size) { "a trusted key is listed more than once" }
        }
    }

    private fun parse(path: String, text: String): List<TrustedKey> =
        Json.decodeFromString<KeysFile>(text).keys.map { record ->
            val hex = record.publicKey
            require(hex.length == 64 && hex.all { it.digitToIntOrNull(16) != null }) {
                "$path: public key must be 64 hexadecimal characters"
            }
            val key = TrustedKey(ByteArray(32) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }, record.status)
            // The ID is written out so a reader can match it to a package, and checked so a mistyped key is caught here.
            require(key.id == record.id) { "$path: key ID ${record.id} does not belong to its public key (${key.id})" }
            key
        }

    @Serializable
    private class KeysFile(
        /** Free text for whoever edits the file. Never read. */
        @Suppress("unused") val note: String = "",
        val keys: List<KeyRecord>,
    )

    @Serializable
    private class KeyRecord(val id: String, val publicKey: String, val status: KeyStatus)
}
